package bgu.spl.net.impl.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for Database
 * Tests user registration, login, logout, and thread safety
 * Note: These tests assume SQL server is not running (executeSQL will return
 * errors)
 */
public class DatabaseTest {

    private Database db;

    @BeforeEach
    public void setUp() {
        db = Database.getInstance();
        // Note: Database is a singleton, so state persists between tests
        // Consider using unique usernames per test
    }

    @Test
    public void testRegisterNewUser() {
        int connectionId = 100;
        String username = "newuser100";
        String password = "pass123";

        LoginStatus status = db.login(connectionId, username, password);
        assertEquals(LoginStatus.ADDED_NEW_USER, status,
                "First login with new credentials should register new user");
    }

    @Test
    public void testLoginExistingUserCorrectPassword() {
        int connectionId1 = 200;
        int connectionId2 = 201;
        String username = "user200";
        String password = "password";

        // First login - register
        LoginStatus status1 = db.login(connectionId1, username, password);
        assertEquals(LoginStatus.ADDED_NEW_USER, status1);

        // Logout
        db.logout(connectionId1);

        // Second login - should succeed
        LoginStatus status2 = db.login(connectionId2, username, password);
        assertEquals(LoginStatus.LOGGED_IN_SUCCESSFULLY, status2,
                "Login with correct password should succeed");
    }

    @Test
    public void testLoginWrongPassword() {
        int connectionId1 = 300;
        int connectionId2 = 301;
        String username = "user300";
        String correctPassword = "correct";
        String wrongPassword = "wrong";

        // Register user
        db.login(connectionId1, username, correctPassword);
        db.logout(connectionId1);

        // Try login with wrong password
        LoginStatus status = db.login(connectionId2, username, wrongPassword);
        assertEquals(LoginStatus.WRONG_PASSWORD, status,
                "Login with wrong password should fail");
    }

    @Test
    public void testUserAlreadyLoggedIn() {
        int connectionId1 = 400;
        int connectionId2 = 401;
        String username = "user400";
        String password = "pass";

        // First login
        db.login(connectionId1, username, password);

        // Second login attempt (without logout)
        LoginStatus status = db.login(connectionId2, username, password);
        assertEquals(LoginStatus.ALREADY_LOGGED_IN, status,
                "User already logged in should return appropriate status");

        // Cleanup
        db.logout(connectionId1);
    }

    @Test
    public void testClientAlreadyConnected() {
        int connectionId = 500;
        String username1 = "user500a";
        String username2 = "user500b";
        String password = "pass";

        // First login
        db.login(connectionId, username1, password);

        // Try to login again with same connection ID
        LoginStatus status = db.login(connectionId, username2, password);
        assertEquals(LoginStatus.CLIENT_ALREADY_CONNECTED, status,
                "Connection already in use should be rejected");

        // Cleanup
        db.logout(connectionId);
    }

    @Test
    public void testLogout() {
        int connectionId = 600;
        String username = "user600";
        String password = "pass";

        // Login
        db.login(connectionId, username, password);

        // Logout
        db.logout(connectionId);

        // Should be able to login again
        LoginStatus status = db.login(connectionId, username, password);
        assertTrue(status == LoginStatus.LOGGED_IN_SUCCESSFULLY ||
                status == LoginStatus.CLIENT_ALREADY_CONNECTED,
                "Should be able to login again after logout");

        // Cleanup
        db.logout(connectionId);
    }

    @Test
    public void testLogoutNonExistentConnection() {
        // Should not throw exception
        assertDoesNotThrow(() -> db.logout(99999),
                "Logout of non-existent connection should not throw");
    }

    @Test
    public void testConcurrentLoginsDifferentUsers() throws InterruptedException {
        int numThreads = 10;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger newUserCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int id = 700 + i;
            new Thread(() -> {
                try {
                    LoginStatus status = db.login(id, "user" + id, "pass" + id);
                    if (status == LoginStatus.ADDED_NEW_USER) {
                        newUserCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(numThreads, newUserCount.get(),
                "All concurrent new user logins should succeed");

        // Cleanup
        for (int i = 0; i < numThreads; i++) {
            db.logout(700 + i);
        }
    }

    @Test
    public void testConcurrentLoginSameUser() throws InterruptedException {
        int connectionId = 800;
        String username = "user800";
        String password = "pass800";

        // Register user first
        db.login(connectionId, username, password);
        db.logout(connectionId);

        int numThreads = 5;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger alreadyLoggedInCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int connId = 801 + i;
            new Thread(() -> {
                try {
                    LoginStatus status = db.login(connId, username, password);
                    if (status == LoginStatus.LOGGED_IN_SUCCESSFULLY) {
                        successCount.incrementAndGet();
                    } else if (status == LoginStatus.ALREADY_LOGGED_IN) {
                        alreadyLoggedInCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        // Only one should succeed, others should get ALREADY_LOGGED_IN
        assertEquals(1, successCount.get(),
                "Only one concurrent login should succeed");
        assertEquals(numThreads - 1, alreadyLoggedInCount.get(),
                "Other logins should get ALREADY_LOGGED_IN");

        // Cleanup - find which one succeeded and logout
        for (int i = 0; i < numThreads; i++) {
            db.logout(801 + i);
        }
    }

    @Test
    public void testLoginAfterLogout() {
        int connectionId1 = 900;
        int connectionId2 = 901;
        String username = "user900";
        String password = "pass900";

        // First login
        LoginStatus status1 = db.login(connectionId1, username, password);
        assertTrue(status1 == LoginStatus.ADDED_NEW_USER ||
                status1 == LoginStatus.LOGGED_IN_SUCCESSFULLY);

        // Logout
        db.logout(connectionId1);

        // Login again with different connection
        LoginStatus status2 = db.login(connectionId2, username, password);
        assertEquals(LoginStatus.LOGGED_IN_SUCCESSFULLY, status2,
                "Should be able to login after logout");

        // Cleanup
        db.logout(connectionId2);
    }

    @Test
    public void testMultipleUsersConcurrent() throws InterruptedException {
        int numUsers = 20;
        CountDownLatch latch = new CountDownLatch(numUsers);
        AtomicInteger successfulLogins = new AtomicInteger(0);

        for (int i = 0; i < numUsers; i++) {
            final int id = 1000 + i;
            new Thread(() -> {
                try {
                    LoginStatus status = db.login(id, "multiuser" + id, "pass" + id);
                    if (status == LoginStatus.ADDED_NEW_USER ||
                            status == LoginStatus.LOGGED_IN_SUCCESSFULLY) {
                        successfulLogins.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(numUsers, successfulLogins.get(),
                "All different users should login successfully");

        // Cleanup
        for (int i = 0; i < numUsers; i++) {
            db.logout(1000 + i);
        }
    }

    @Test
    public void testDatabaseIsSingleton() {
        Database db1 = Database.getInstance();
        Database db2 = Database.getInstance();

        assertSame(db1, db2, "Database should be a singleton");
    }
}
