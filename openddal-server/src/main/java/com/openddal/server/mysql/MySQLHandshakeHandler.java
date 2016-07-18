/*
 * Copyright 2014-2016 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openddal.server.mysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openddal.engine.Constants;
import com.openddal.engine.SysProperties;
import com.openddal.jdbc.JdbcDriver;
import com.openddal.server.ProtocolHandler;
import com.openddal.server.ProtocolTransport;
import com.openddal.server.Session;
import com.openddal.server.mysql.auth.Privilege;
import com.openddal.server.mysql.auth.PrivilegeDefault;
import com.openddal.server.mysql.proto.ERR;
import com.openddal.server.mysql.proto.Flags;
import com.openddal.server.mysql.proto.Handshake;
import com.openddal.server.mysql.proto.HandshakeResponse;
import com.openddal.server.mysql.proto.OK;
import com.openddal.server.util.CharsetUtil;
import com.openddal.server.util.ErrorCode;
import com.openddal.server.util.StringUtil;
import com.openddal.util.JdbcUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

/**
 * @author <a href="mailto:jorgie.mail@gmail.com">jorgie li</a>
 *
 */
@Sharable
public class MySQLHandshakeHandler extends ProtocolHandler {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLHandshakeHandler.class);
    private final AtomicLong connIdGenerator = new AtomicLong(0);
    private final AttributeKey<MySQLSession> TMP_SESSION_KEY = AttributeKey.valueOf("_AUTHTMP_SESSION_KEY");
    private static final String SEED_KEY = "seed";
    private Privilege privilege = PrivilegeDefault.getPrivilege();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ByteBuf out = ctx.alloc().buffer();
        Handshake handshake = new Handshake();
        handshake.sequenceId = 0;
        handshake.protocolVersion = MySQLServer.PROTOCOL_VERSION;
        handshake.serverVersion = MySQLServer.SERVER_VERSION;
        handshake.connectionId = connIdGenerator.incrementAndGet();
        handshake.challenge1 = getRandomString(8);
        //handshake.capabilityFlags = 4294951423L;
        handshake.characterSet = CharsetUtil.getIndex(MySQLServer.DEFAULT_CHARSET);
        handshake.statusFlags = Flags.SERVER_STATUS_AUTOCOMMIT;
        handshake.challenge2 = getRandomString(12);
        handshake.authPluginDataLength = 21;
        handshake.authPluginName = "mysql_native_password";
        handshake.capabilityFlags = getServerCapabilities();

        MySQLSession temp = new MySQLSession();
        temp.setHandshake(handshake);
        temp.setAttachment(SEED_KEY, handshake.challenge1);
        ctx.attr(TMP_SESSION_KEY).set(temp);
        out.writeBytes(handshake.toPacket());
        ctx.writeAndFlush(out);
    
    }




    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ProtocolTransport transport = new ProtocolTransport(ctx.channel(), (ByteBuf) msg);
        if(transport.getSession() == null) {
            userExecutor.execute(new AuthTask(ctx, transport));
        } else {
            ctx.fireChannelRead(msg);
        }
    
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Session session = ctx.channel().attr(Session.CHANNEL_SESSION_KEY).get();
        if (session != null) {
            JdbcUtils.closeSilently(session.getEngineConnection());
        }
    }

    /**
     * @param authReply
     * @return
     * @throws SQLException
     */
    private Connection connectEngine(HandshakeResponse authReply) throws SQLException {
        Properties prop = new Properties();
        prop.setProperty("user", authReply.username);
        prop.setProperty("password", authReply.authResponse);
        String url = Constants.START_URL + SysProperties.ENGINE_CONFIG_LOCATION;
        Connection connect = JdbcDriver.load().connect(url, prop);
        return connect;
    }

    private String getRandomString(int length) {
        char[] chars = new char[length];
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            chars[i] = (char) random.nextInt(127);
        }
        return String.valueOf(chars);
    }
    
    protected int getServerCapabilities() {
        int flag = 0;
        flag |= Flags.CLIENT_LONG_PASSWORD;
        flag |= Flags.CLIENT_FOUND_ROWS;
        flag |= Flags.CLIENT_LONG_FLAG;
        flag |= Flags.CLIENT_CONNECT_WITH_DB;
        // flag |= Flags.CLIENT_NO_SCHEMA;
        // flag |= Flags.CLIENT_COMPRESS;
        flag |= Flags.CLIENT_ODBC;
        // flag |= Flags.CLIENT_LOCAL_FILES;
        flag |= Flags.CLIENT_IGNORE_SPACE;
        flag |= Flags.CLIENT_PROTOCOL_41;
        flag |= Flags.CLIENT_INTERACTIVE;
        // flag |= Flags.CLIENT_SSL;
        flag |= Flags.CLIENT_IGNORE_SIGPIPE;
        flag |= Flags.CLIENT_TRANSACTIONS;
        // flag |= Flags.CLIENT_RESERVED;
        flag |= Flags.CLIENT_SECURE_CONNECTION;
        return flag;
    }
        
    /**
     * @param channel
     * @param buf
     * @return
     */
    private void success(Channel channel) {
        ByteBuf out = channel.alloc().buffer();
        OK ok = new OK();
        ok.sequenceId = 2;
        ok.setStatusFlag(Flags.SERVER_STATUS_AUTOCOMMIT);
        out.writeBytes(ok.toPacket());
        channel.writeAndFlush(out);
    }
    
    /**
     * Execute the processor in user threads.
     */
    class AuthTask implements Runnable {
        private ChannelHandlerContext ctx;
        private ProtocolTransport transport;

        AuthTask(ChannelHandlerContext ctx, ProtocolTransport transport) {
            this.ctx = ctx;
            this.transport = transport;
        }

        @Override
        public void run() {
            MySQLSession session = ctx.attr(TMP_SESSION_KEY).getAndRemove();
            HandshakeResponse authReply = null;
            try {
                byte[] packet = new byte[transport.in.readableBytes()];
                transport.in.readBytes(packet);
                authReply = HandshakeResponse.loadFromPacket(packet);
                
                if (!authReply.hasCapabilityFlag(Flags.CLIENT_PROTOCOL_41)) {
                    error(ErrorCode.ER_NOT_SUPPORTED_AUTH_MODE, "We do not support Protocols under 4.1");
                    return;
                }
                
                if (!privilege.userExists(authReply.username)) {
                    error(ErrorCode.ER_ACCESS_DENIED_ERROR,
                            "Access denied for user '" + authReply.username + "'");
                    return;
                }
                
                if (!StringUtil.isEmpty(authReply.schema) 
                        && !privilege.schemaExists(authReply.username, authReply.schema)) {
                    String s = "Access denied for user '" + authReply.username
                            + "' to database '" + authReply.schema + "'";
                    error(ErrorCode.ER_DBACCESS_DENIED_ERROR, s);
                    return;
                }

                if(!privilege.checkPassword(authReply.username, authReply.authResponse,
                        (String) session.getAttachment(SEED_KEY))) {
                    error(ErrorCode.ER_ACCESS_DENIED_ERROR,
                            "Access denied for user '" + authReply.username + "'");
                    return;
                }
                Connection connect = connectEngine(authReply);
                session.setHandshakeResponse(authReply);
                session.setEngineConnection(connect);
                session.bind(ctx.channel());
                session.setAttachment("remoteAddress", ctx.channel().remoteAddress().toString());
                session.setAttachment("localAddress", ctx.channel().localAddress().toString());
                success(ctx.channel());
            } catch (Exception e) {
                String errMsg = authReply == null ? e.getMessage()
                        : "Access denied for user '" + authReply.username + "' to database '" + authReply.schema + "'";
                LOGGER.error("Authorize failed. " + errMsg, e);
                error(ErrorCode.ER_DBACCESS_DENIED_ERROR, errMsg);
            } finally {
                ctx.writeAndFlush(transport.out);
                transport.in.release();
            }        
        }
        
        public void error(int errno, String msg) {
            transport.out.clear();
            ERR err = new ERR();
            err.errorCode = errno;
            err.errorMessage = msg;
            transport.out.writeBytes(err.toPacket());
        } 
    }



}
