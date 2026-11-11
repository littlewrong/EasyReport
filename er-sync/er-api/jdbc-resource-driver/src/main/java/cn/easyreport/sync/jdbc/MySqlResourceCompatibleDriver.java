package cn.easyreport.sync.jdbc;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Version-two Resource entry point, intentionally absent from the BIT-only driver JAR.
 * Configuring this class makes stale deployments fail driver loading before a scan can
 * silently use the vendor's binary or TIME character conversions.
 */
public final class MySqlResourceCompatibleDriver extends MySqlBitCompatibleDriver {
    static {
        try { DriverManager.registerDriver(new MySqlResourceCompatibleDriver()); }
        catch (SQLException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public MySqlResourceCompatibleDriver() { super(); }

    MySqlResourceCompatibleDriver(Driver delegate) { super(delegate); }
}
