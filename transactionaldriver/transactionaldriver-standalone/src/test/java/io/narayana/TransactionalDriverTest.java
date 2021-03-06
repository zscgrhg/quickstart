/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package io.narayana;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;

import javax.transaction.TransactionManager;

import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.internal.jdbc.TransactionalDriverXAConnection;
import com.arjuna.ats.jta.common.jtaPropertyManager;

import io.narayana.recovery.RecoverySetupUtil;
import io.narayana.util.CodeUtils;
import io.narayana.util.DBUtils;

/**
 * Tests running commit and rollback scenarios for showcases
 * of managing database connections with use of the Narayana transaction driver.
 */
@RunWith(BMUnitRunner.class)
public class TransactionalDriverTest {
    Connection conn1, conn2;

    @Before
    public void setUp() {
        conn1 = DBUtils.getConnection(DBUtils.DB_1);
        conn2 = DBUtils.getConnection(DBUtils.DB_2);

        CodeUtils.swallowException(() -> DBUtils.dropTable(conn1));
        CodeUtils.swallowException(() -> DBUtils.dropTable(conn2));
        DBUtils.createTable(conn1);
        DBUtils.createTable(conn2);
    }

    @After
    public void tearDown() throws Exception {
        // cleaning possible active global transaction
        TransactionManager txn = com.arjuna.ats.jta.TransactionManager.transactionManager();
        if(txn != null) {
            if(txn.getStatus() == javax.transaction.Status.STATUS_ACTIVE)
                txn.rollback();
            if(txn.getStatus() != javax.transaction.Status.STATUS_NO_TRANSACTION)
                txn.suspend();
        }
        // cleaning recovery settings
        jtaPropertyManager.getJTAEnvironmentBean().setXaResourceRecoveryClassNames(null);
        // cleaning database
        if(DBUtils.h2LockConnection != null) { // H2 workaround - force to clean
            CodeUtils.swallowException(() -> DBUtils.h2LockConnection.rollback());
            CodeUtils.swallowClose(DBUtils.h2LockConnection);

            Field f = DBUtils.h2LockConnection.getClass().getDeclaredField("_transactionalDriverXAConnectionConnection");
            f.setAccessible(true);
            TransactionalDriverXAConnection conn1Hack = (TransactionalDriverXAConnection) f.get(DBUtils.h2LockConnection);
            conn1Hack.closeCloseCurrentConnection();

            DBUtils.h2LockConnection = null;
        }
        // closing connections
        CodeUtils.closeMultiple(conn1, conn2);
        conn1 = null;
        conn2 = null;
    }

    @Test
    public void localTxnCommit() throws Exception {
        new JdbcLocalTransaction().process(() -> {});

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertTrue("First database does not contain data as expected", rs1.next());
        Assert.assertTrue("Second database does not contain data as expected", rs2.next());
    }

    @Test
    public void localTxnRollback() throws Exception {
        try {
            new JdbcLocalTransaction().process(() -> {throw new RuntimeException("expected");});
        } catch (Exception e) {
            checkcException(e);
        }

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertTrue("First database does not contain data as expected", rs1.next());
        Assert.assertFalse("Second database contain data which is not expected", rs2.next());
    }

    @Test
    public void transactionManagerCommit() throws Exception {
        new ManagedTransaction().process(() -> {});

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertTrue("First database does not contain data as expected to be commited", rs1.next());
        Assert.assertTrue("Second database does not contain data as expected to be commited", rs2.next());
    }

    @Test
    public void transactionManagerRollback() throws Exception {
        try {
            new ManagedTransaction().process(() -> {throw new RuntimeException("expected");});
        } catch (Exception e) {
            checkcException(e);
        }

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertFalse("First database contains data which is not expected as rolled-back", rs1.next());
        Assert.assertFalse("Second database contains data which is not expected as rolled-back", rs2.next());
    }

    @Test
    public void transactionDriverProvidedCommit() throws Exception {
        new DriverProvidedXADataSource().process(() -> {});

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertTrue("First database does not contain data as expected to be commited", rs1.next());
        Assert.assertTrue("Second database does not contain data as expected to be commited", rs2.next());
    }

    @Test
    public void transactionDriverProvidedRollback() throws Exception {
        try {
            new DriverProvidedXADataSource().process(() -> {throw new RuntimeException("expected");});
        } catch (Exception e) {
            checkcException(e);
        }

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertFalse("First database contains data which is not expected as rolled-back", rs1.next());
        Assert.assertFalse("Second database contains data which is not expected as rolled-back", rs2.next());
    }

    @Test
    public void transactionDriverIndirectCommit() throws Exception {
        new DriverIndirectRecoverable().process(() -> {});

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertTrue("First database does not contain data as expected to be commited", rs1.next());
        Assert.assertTrue("Second database does not contain data as expected to be commited", rs2.next());
    }

    @Test
    public void transactionDriverIndirectRollback() throws Exception {
        try {
            new DriverIndirectRecoverable().process(() -> {throw new RuntimeException("expected");});
        } catch (Exception e) {
            checkcException(e);
        }

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertFalse("First database contains data which is not expected as rolled-back", rs1.next());
        Assert.assertFalse("Second database contains data which is not expected as rolled-back", rs2.next());
    }

    @Test
    public void transactionDriverDirectRecoverableCommit() throws Exception {
        new DriverDirectRecoverable().process(() -> {});

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertTrue("First database does not contain data as expected to be commited", rs1.next());
        Assert.assertTrue("Second database does not contain data as expected to be commited", rs2.next());
    }

    @Test
    public void transactionDriverDirectRecoverableRollback() throws Exception {
        try {
            new DriverDirectRecoverable().process(() -> {throw new RuntimeException("expected");});
        } catch (Exception e) {
            checkcException(e);
        }

        ResultSet rs1 = DBUtils.select(conn1);
        ResultSet rs2 = DBUtils.select(conn2);
        Assert.assertFalse("First database contains data which is not expected as rolled-back", rs1.next());
        Assert.assertFalse("Second database contains data which is not expected as rolled-back", rs2.next());
    }

    @BMScript("xaexception.rmfail")
    @Test
    public void transactionDriverDirectRecoverableRecovery() throws Exception {
        RecoveryManager recoveryManager = RecoverySetupUtil.simpleRecoveryIntialize();

        new DriverDirectRecoverable().process(() -> {});

        ResultSet rs1 = DBUtils.select(conn1);
        Assert.assertFalse("First database " + conn1 + " is committed even commit was expected to fail", rs1.next());

        RecoverySetupUtil.runRecovery(recoveryManager);

        rs1 = DBUtils.select(conn1);
        Assert.assertTrue("First database does not contain data as expected to be commited", rs1.next());
    }

    @BMScript("xaexception.rmfail")
    @Test
    public void transactionDriverIndirectRecoverableRecovery() throws Exception {
        RecoveryManager recoveryManager = RecoverySetupUtil.jdbcXARecoveryIntialize();

        new DriverIndirectRecoverable().process(() -> {});

        ResultSet rs1 = DBUtils.select(conn1);
        Assert.assertFalse("First database " + conn1 + " is committed even commit was expected to fail", rs1.next());

        RecoverySetupUtil.runRecovery(recoveryManager);

        rs1 = DBUtils.select(conn1);
        Assert.assertTrue("First database does not contain data as expected to be commited", rs1.next());
    }

    @BMScript("xaexception.rmfail")
    @Test
    public void transactionDriverIndirectRecoverableRecovery2() throws Exception {
        RecoveryManager recoveryManager = RecoverySetupUtil.basicXARecoveryIntialize();

        new DriverIndirectRecoverable().process(() -> {});

        ResultSet rs1 = DBUtils.select(conn1);
        Assert.assertFalse("First database " + conn1 + " is committed even commit was expected to fail", rs1.next());

        RecoverySetupUtil.runRecovery(recoveryManager);

        rs1 = DBUtils.select(conn1);
        Assert.assertTrue("First database does not contain data as expected to be commited", rs1.next());
    }


    private void checkcException(Exception e) {
        if (!e.getMessage().toLowerCase().contains("expected"))
            Assert.fail("Exception message does not contain 'expected' but it's '"
                + e.getClass().getName() + ":" + e.getMessage() + "'");
    }
}
