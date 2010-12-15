/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.thrift;

 import java.io.IOException;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;

import javax.security.sasl.SaslException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.security.SaslRpcServer;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.SaslRpcServer.AuthMethod;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSaslClientTransport;
import org.apache.thrift.transport.TSaslServerTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.TTransportFactory;


 /**
  * Functions that bridge Thrift's SASL transports to Hadoop's
  * SASL callback handlers and authentication classes.
  */
 public class HadoopThriftAuthBridge20S extends HadoopThriftAuthBridge {
   static final Log LOG = LogFactory.getLog(HadoopThriftAuthBridge.class);

   @Override
   public Client createClient() {
     return new Client();
   }

   @Override
   public Server createServer(String keytabFile, String principalConf) throws TTransportException {
     return new Server(keytabFile, principalConf);
   }

   public static class Client extends HadoopThriftAuthBridge.Client {
     /**
      * Create a client-side SASL transport that wraps an underlying transport.
      *
      * @param method The authentication method to use. Currently only KERBEROS is
      *               supported.
      * @param serverPrincipal The Kerberos principal of the target server.
      * @param underlyingTransport The underlying transport mechanism, usually a TSocket.
      */
     @Override
     public TTransport createClientTransport(
       String principalConfig, String host,
       String methodStr, TTransport underlyingTransport)
       throws IOException {
       AuthMethod method = AuthMethod.valueOf(AuthMethod.class, methodStr);

       switch (method) {
         case KERBEROS:
           String serverPrincipal = SecurityUtil.getServerPrincipal(principalConfig, host);
           String names[] = SaslRpcServer.splitKerberosName(serverPrincipal);
           if (names.length != 3) {
             throw new IOException(
               "Kerberos principal name does NOT have the expected hostname part: "
                 + serverPrincipal);
           }
           try {
             TTransport saslTransport = new TSaslClientTransport(
               method.getMechanismName(),
               null,
               names[0], names[1],
               SaslRpcServer.SASL_PROPS, null,
               underlyingTransport);
             return new TUGIAssumingTransport(saslTransport, UserGroupInformation.getCurrentUser());
           } catch (SaslException se) {
             throw new IOException("Could not instantiate SASL transport", se);
           }

         default:
           throw new IOException("Unsupported authentication method: " +method);
       }
     }
   }

   public static class Server extends HadoopThriftAuthBridge.Server {
     private final UserGroupInformation realUgi;

     /**
      * TODO: javadoc
      */
     private Server(String keytabFile, String principalConf)
       throws TTransportException {
       if (keytabFile == null || keytabFile.isEmpty()) {
         throw new TTransportException("No keytab specified");
       }
       if (principalConf == null || principalConf.isEmpty()) {
         throw new TTransportException("No principal specified");
       }

       // Login from the keytab
       String kerberosName;
       try {
         kerberosName = SecurityUtil.getServerPrincipal(
           principalConf, null);
         UserGroupInformation.loginUserFromKeytab(
             kerberosName, keytabFile);
         realUgi = UserGroupInformation.getLoginUser();
         assert realUgi.isFromKeytab();
       } catch (IOException ioe) {
         throw new TTransportException(ioe);
       }
     }

     /**
      * Create a TTransportFactory that, upon connection of a client socket,
      * negotiates a Kerberized SASL transport. The resulting TTransportFactory
      * can be passed as both the input and output transport factory when
      * instantiating a TThreadPoolServer, for example.
      *
      */
     @Override
     public TTransportFactory createTransportFactory() throws TTransportException
     {
       // Parse out the kerberos principal, host, realm.
       String kerberosName = realUgi.getUserName();
       final String names[] = SaslRpcServer.splitKerberosName(kerberosName);
       if (names.length != 3) {
         throw new TTransportException("Kerberos principal should have 3 parts: "  +kerberosName);
       }

       TSaslServerTransport.Factory transFactory = new TSaslServerTransport.Factory();
       transFactory.addServerDefinition(
         AuthMethod.KERBEROS.getMechanismName(),
         names[0], names[1],  // two parts of kerberos principal
         SaslRpcServer.SASL_PROPS,
         new SaslRpcServer.SaslGssCallbackHandler());

       return new TUGIAssumingTransportFactory(transFactory, realUgi);
     }

     /**
      * Wrap a TProcessor in such a way that, before processing any RPC, it
      * assumes the UserGroupInformation of the user authenticated by
      * the SASL transport.
      */
     @Override
     public TProcessor wrapProcessor(TProcessor processor) {
       return new TUGIAssumingProcessor(processor);
     }

     /**
      * Processor that pulls the SaslServer object out of the transport, and
      * assumes the remote user's UGI before calling through to the original
      * processor.
      *
      * This is used on the server side to set the UGI for each specific call.
      */
     private class TUGIAssumingProcessor implements TProcessor {
       final TProcessor wrapped;

       TUGIAssumingProcessor(TProcessor wrapped) {
         this.wrapped = wrapped;
       }

       public boolean process(final TProtocol inProt, final TProtocol outProt) throws TException {
         TTransport trans = inProt.getTransport();
         if (!(trans instanceof TSaslServerTransport)) {
           throw new TException("Unexpected non-SASL transport "  +trans.getClass());
         }
         TSaslServerTransport saslTrans = (TSaslServerTransport)trans;
         String authId = saslTrans.getSaslServer().getAuthorizationID();

         try {
           UserGroupInformation clientUgi = UserGroupInformation.createProxyUser(
               authId,  UserGroupInformation.getLoginUser());
           return clientUgi.doAs(new PrivilegedExceptionAction<Boolean>() {
               public Boolean run() {
                 try {
                   return wrapped.process(inProt, outProt);
                 } catch (TException te) {
                   throw new RuntimeException(te);
                 }
               }
             });
         } catch (RuntimeException rte) {
           if (rte.getCause() instanceof TException) {
             throw (TException)rte.getCause();
           }
           throw rte;
         } catch (InterruptedException ie) {
           throw new RuntimeException(ie); // unexpected!
         } catch (IOException ioe) {
           throw new RuntimeException(ioe); // unexpected!
         }
       }
     }

   }

   /**
    * A TransportFactory that wraps another one, but assumes a specified UGI
    * before calling through.
    *
    * This is used on the server side to assume the server's Principal when accepting
    * clients.
    */
   private static class TUGIAssumingTransportFactory extends TTransportFactory {
     private final UserGroupInformation ugi;
     private final TTransportFactory wrapped;

     public TUGIAssumingTransportFactory(TTransportFactory wrapped, UserGroupInformation ugi) {
       assert wrapped != null;
       assert ugi != null;

       this.wrapped = wrapped;
       this.ugi = ugi;
     }

     @Override
     public TTransport getTransport(final TTransport trans) {
       return ugi.doAs(new PrivilegedAction<TTransport>() {
         public TTransport run() {
           return wrapped.getTransport(trans);
         }
       });
     }
   }

   /**
    * The Thrift SASL transports call Sasl.createSaslServer and Sasl.createSaslClient
    * inside open(). So, we need to assume the correct UGI when the transport is opened
    * so that the SASL mechanisms have access to the right principal. This transport
    * wraps the Sasl transports to set up the right UGI context for open().
    *
    * This is used on the client side, where the API explicitly opens a transport to
    * the server.
    */
   private static class TUGIAssumingTransport extends TFilterTransport {
     private final UserGroupInformation ugi;

     public TUGIAssumingTransport(TTransport wrapped, UserGroupInformation ugi) {
       super(wrapped);
       this.ugi = ugi;
     }

     @Override
     public void open() throws TTransportException {
       try {
         ugi.doAs(new PrivilegedExceptionAction<Void>() {
           public Void run() {
             try {
               wrapped.open();
             } catch (TTransportException tte) {
               // Wrap the transport exception in an RTE, since UGI.doAs() then goes
               // and unwraps this for us out of the doAs block. We then unwrap one
               // more time in our catch clause to get back the TTE. (ugh)
               throw new RuntimeException(tte);
             }
             return null;
           }
         });
       } catch (IOException ioe) {
         assert false : "Never thrown!";
         throw new RuntimeException("Received an ioe we never threw!", ioe);
       } catch (InterruptedException ie) {
         assert false : "We never expect to see an InterruptedException thrown in this block";
         throw new RuntimeException("Received an ie we never threw!", ie);
       } catch (RuntimeException rte) {
         if (rte.getCause() instanceof TTransportException) {
           throw (TTransportException)rte.getCause();
         } else {
           throw rte;
         }
       }
     }
   }

   /**
    * Transport that simply wraps another transport.
    * This is the equivalent of FilterInputStream for Thrift transports.
    */
   private static class TFilterTransport extends TTransport {
     protected final TTransport wrapped;

     public TFilterTransport(TTransport wrapped) {
       this.wrapped = wrapped;
     }

     @Override
     public void open() throws TTransportException {
       wrapped.open();
     }

     @Override
     public boolean isOpen() {
       return wrapped.isOpen();
     }

     @Override
     public boolean peek() {
       return wrapped.peek();
     }

     @Override
     public void close() {
       wrapped.close();
     }

     @Override
     public int read(byte[] buf, int off, int len) throws TTransportException {
       return wrapped.read(buf, off, len);
     }

     @Override
     public int readAll(byte[] buf, int off, int len) throws TTransportException {
       return wrapped.readAll(buf, off, len);
     }

     @Override
     public void write(byte[] buf) throws TTransportException {
       wrapped.write(buf);
     }

     @Override
     public void write(byte[] buf, int off, int len) throws TTransportException {
       wrapped.write(buf, off, len);
     }

     @Override
     public void flush() throws TTransportException {
       wrapped.flush();
     }

     @Override
     public byte[] getBuffer() {
       return wrapped.getBuffer();
     }

     @Override
     public int getBufferPosition() {
       return wrapped.getBufferPosition();
     }

     @Override
     public int getBytesRemainingInBuffer() {
       return wrapped.getBytesRemainingInBuffer();
     }

     @Override
     public void consumeBuffer(int len) {
       wrapped.consumeBuffer(len);
     }
   }
 }
