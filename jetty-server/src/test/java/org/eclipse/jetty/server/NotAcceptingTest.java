//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;
  
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class NotAcceptingTest
{
    @Test
    public void testServerConnectorBlockingAccept() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server,1,1);
        connector.setPort(0);
        connector.setIdleTimeout(500);
        connector.setAcceptQueueSize(10);
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();
        
        try(Socket client0 = new Socket("localhost",connector.getLocalPort());)
        {
            HttpTester.Input in0 = HttpTester.from(client0.getInputStream());

            client0.getOutputStream().write("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            String uri = handler.exchange.exchange("data");
            assertThat(uri,is("/one"));
            HttpTester.Response response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("data"));
            
            connector.setAccepting(false);

            // 0th connection still working
            client0.getOutputStream().write("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            uri = handler.exchange.exchange("more data");
            assertThat(uri,is("/two"));
            response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("more data"));
            

            try(Socket client1 = new Socket("localhost",connector.getLocalPort());)
            {
                // can't stop next connection being accepted
                HttpTester.Input in1 = HttpTester.from(client1.getInputStream());
                client1.getOutputStream().write("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                uri = handler.exchange.exchange("new connection");
                assertThat(uri,is("/three"));
                response = HttpTester.parseResponse(in1);
                assertThat(response.getStatus(),is(200));
                assertThat(response.getContent(),is("new connection"));
                

                try(Socket client2 = new Socket("localhost",connector.getLocalPort());)
                {

                    HttpTester.Input in2 = HttpTester.from(client2.getInputStream());
                    client2.getOutputStream().write("GET /four HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());

                    try
                    {
                        uri = handler.exchange.exchange("delayed connection",500,TimeUnit.MILLISECONDS);
                        Assert.fail(uri);
                    }
                    catch(TimeoutException e)
                    {
                        // Can we accept the original?
                        connector.setAccepting(true); 
                        uri = handler.exchange.exchange("delayed connection");
                        assertThat(uri,is("/four"));
                        response = HttpTester.parseResponse(in2);
                        assertThat(response.getStatus(),is(200));
                        assertThat(response.getContent(),is("delayed connection"));
                    }
                }
            }
        }
    }
    

    @Test
    public void testLocalConnector() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        connector.setIdleTimeout(500);
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();
        
        try(LocalEndPoint client0 = connector.connect())
        {
            client0.addInputAndExecute(BufferUtil.toBuffer("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n"));
            String uri = handler.exchange.exchange("data");
            assertThat(uri,is("/one"));
            HttpTester.Response response = HttpTester.parseResponse(client0.getResponse());
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("data"));
            
            connector.setAccepting(false);

            // 0th connection still working
            client0.addInputAndExecute(BufferUtil.toBuffer("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n"));
            uri = handler.exchange.exchange("more data");
            assertThat(uri,is("/two"));
            response = HttpTester.parseResponse(client0.getResponse());
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("more data"));
            

            try(LocalEndPoint client1 = connector.connect())
            {
                // can't stop next connection being accepted
                client1.addInputAndExecute(BufferUtil.toBuffer("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n"));
                uri = handler.exchange.exchange("new connection");
                assertThat(uri,is("/three"));
                response = HttpTester.parseResponse(client1.getResponse());
                assertThat(response.getStatus(),is(200));
                assertThat(response.getContent(),is("new connection"));
                

                try(LocalEndPoint client2 = connector.connect())
                {
                    client2.addInputAndExecute(BufferUtil.toBuffer("GET /four HTTP/1.1\r\nHost:localhost\r\n\r\n"));

                    try
                    {
                        uri = handler.exchange.exchange("delayed connection",500,TimeUnit.MILLISECONDS);
                        Assert.fail(uri);
                    }
                    catch(TimeoutException e)
                    {
                        // Can we accept the original?
                        connector.setAccepting(true); 
                        uri = handler.exchange.exchange("delayed connection");
                        assertThat(uri,is("/four"));
                        response = HttpTester.parseResponse(client2.getResponse());
                        assertThat(response.getStatus(),is(200));
                        assertThat(response.getContent(),is("delayed connection"));
                    }
                }
            }
        }
    }
   
    @Test
    public void testServerConnectorAsyncAccept() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server,0,1);
        connector.setPort(0);
        connector.setIdleTimeout(500);
        connector.setAcceptQueueSize(10);
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();
        
        try(Socket client0 = new Socket("localhost",connector.getLocalPort());)
        {
            HttpTester.Input in0 = HttpTester.from(client0.getInputStream());

            client0.getOutputStream().write("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            String uri = handler.exchange.exchange("data");
            assertThat(uri,is("/one"));
            HttpTester.Response response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("data"));
            
            connector.setAccepting(false);

            // 0th connection still working
            client0.getOutputStream().write("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            uri = handler.exchange.exchange("more data");
            assertThat(uri,is("/two"));
            response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(),is(200));
            assertThat(response.getContent(),is("more data"));
            

            try(Socket client1 = new Socket("localhost",connector.getLocalPort());)
            {
                HttpTester.Input in1 = HttpTester.from(client1.getInputStream());
                client1.getOutputStream().write("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                
                try
                {
                    uri = handler.exchange.exchange("delayed connection",500,TimeUnit.MILLISECONDS);
                    Assert.fail(uri);
                }
                catch(TimeoutException e)
                {
                    // Can we accept the original?
                    connector.setAccepting(true); 
                    uri = handler.exchange.exchange("delayed connection");
                    assertThat(uri,is("/three"));
                    response = HttpTester.parseResponse(in1);
                    assertThat(response.getStatus(),is(200));
                    assertThat(response.getContent(),is("delayed connection"));
                }
            }
        }
    } 
    
    public static class TestHandler extends AbstractHandler
    {
        final Exchanger<String> exchange = new Exchanger<>();
        transient int handled;
        
        public TestHandler()
        {
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                String content = exchange.exchange(baseRequest.getRequestURI());
                baseRequest.setHandled(true);
                handled++;
                response.setContentType("text/html;charset=utf-8");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().print(content);
            }
            catch (InterruptedException e)
            {
                throw new ServletException(e);
            }
        }
        
        public int getHandled()
        {
            return handled;
        }
    }
    
}
