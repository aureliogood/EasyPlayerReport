package com.easyplayerreport;

public interface ReportStore {
    void initialize() throws Exception;
    DatabaseData load() throws Exception;
    void save(DatabaseData data) throws Exception;
    boolean isMigrated() throws Exception;
    void markMigrated() throws Exception;
    void close() throws Exception;
}
