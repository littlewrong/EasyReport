package cn.easyreport.sync.jdbc;

import java.sql.Driver;
import java.util.Properties;

/** Validates the assembled driver and bundled Connector/J without opening a connection. */
public final class DriverJarSmokeTest {
    public static void main(String[] args) throws Exception {
        Driver driver = new MySqlResourceCompatibleDriver();
        if (!driver.acceptsURL("jdbc:mysql://driver-smoke.invalid:3306/fixture")) {
            throw new AssertionError("Real MySQL URL must be accepted");
        }
        if (driver.acceptsURL("jdbc:postgresql://driver-smoke.invalid/fixture")) {
            throw new AssertionError("Other protocols must not be accepted");
        }
        if (driver.getMajorVersion() != 8 || driver.getMinorVersion() != 0
                || driver.getPropertyInfo("jdbc:mysql://driver-smoke.invalid:3306/fixture", new Properties()).length == 0) {
            throw new AssertionError("Bundled Connector/J must be available");
        }
        System.out.println("Packaged Connector/J smoke test passed (no database connection)");
    }
}
