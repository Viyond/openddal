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
package com.openddal.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.openddal.engine.Session;
import com.openddal.message.DbException;
import com.openddal.message.ErrorCode;
import com.openddal.message.Trace;
import com.openddal.repo.tx.JdbcTransaction;
import com.openddal.util.StatementBuilder;
import com.openddal.value.Value;

/**
 * @author jorgie.li
 */
public abstract class JdbcWorker {
    protected final Session session;
    protected final Trace trace;

    protected final String shardName;
    protected final String sql;
    protected final List<Value> params;

    protected Connection opendConnection;
    protected PreparedStatement opendStatement;
    protected ResultSet opendResultSet;
    protected boolean closed;

    public JdbcWorker(Session session, String shardName, String sql, List<Value> params) {
        super();
        this.session = session;
        this.shardName = shardName;
        this.sql = sql;
        this.params = params;
        this.trace = session.getDatabase().getTrace(Trace.EXECUTOR);

    }

    /**
     * Wrap a SQL exception that occurred while data accessing.
     *
     * @param sql the SQL statement
     * @param ex the exception from the remote database
     * @return the wrapped exception
     */
    protected static DbException wrapException(String operation, String shardName, String sql, Exception ex) {
        SQLException e = DbException.toSQLException(ex);
        return DbException.get(ErrorCode.ERROR_ACCESSING_DATABASE_TABLE_2, e, operation, shardName, sql, e.toString());
    }

    protected Connection doGetConnection(Options options) {
        JdbcTransaction tx = (JdbcTransaction)session.getTransaction();
        ConnectionProvider connProvider = tx.getConnectionProvider();
        return connProvider.getConnection(options);
    }
    
    
    protected void closeConnection(String shardName, Connection conn) {
        JdbcTransaction tx = (JdbcTransaction)session.getTransaction();
        ConnectionProvider connProvider = tx.getConnectionProvider();
        connProvider.closeConnection(conn, Options.build().shardName(shardName));
    }

    /**
     * @return the shardName
     */
    public String getShardName() {
        return shardName;
    }

    /**
     * @return the sql
     */
    public String getSql() {
        return sql;
    }

    /**
     * @return the params
     */
    public List<Value> getParams() {
        return params;
    }

    public void cancel() {
        try {
            if (opendStatement == null) {
                return;
            }
            opendStatement.cancel();
        } catch (Exception e) {

        }
    }
    
    public void close() {
        try {
            if (opendResultSet != null) {
                try {
                    opendResultSet.close();
                    opendResultSet = null;
                } catch (SQLException e) {
                    trace.error(e, "close ResultSet error.");
                }
            }
            if (opendStatement != null) {
                try {
                    opendStatement.close();
                } catch (SQLException e) {
                    trace.error(e, "close statement error.");
                }
            }
            if (opendConnection != null) {
                try {
                    closeConnection(this.shardName, opendConnection);
                } catch (Exception e) {
                    trace.error(e, "close connection error.");
                }
            } 
        } finally {
            opendResultSet = null;
            opendStatement = null;
            opendConnection = null;
        }
    }

    public String explain() {
        StatementBuilder buff = new StatementBuilder();
        buff.append("execute on ").append(shardName);
        buff.append(": ").append(sql);
        if (params != null && params.size() > 0) {
            buff.append(" params: {");
            int i = 1;
            for (Value v : params) {
                buff.appendExceptFirst(", ");
                buff.append(i++).append(": ").append(v.getSQL());
            }
            buff.append('}');
        }
        return buff.toString();
    }

    protected void applyQueryTimeout(Statement stmt) throws SQLException {
        // The session timeout of a query in milliseconds
        int queryTimeout = session.getQueryTimeout();
        if (queryTimeout > 0) {
            int seconds = queryTimeout / 1000;
            trace.debug("apply {0} query time out from statement.", seconds);
            stmt.setQueryTimeout(seconds);
        }
    }

    protected void beforeExecute() {
        close();
    }
}
