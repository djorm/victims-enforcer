/*
 * Copyright (C) 2012 Red Hat Inc.
 *
 * This file is part of enforce-victims-rule for the Maven Enforcer Plugin.
 * enforce-victims-rule is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * enforce-victims-rule is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with enforce-victims-rule.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.redhat.victims.db;

import com.redhat.victims.IOUtils;
import com.redhat.victims.Resources;
import com.redhat.victims.VictimsException;
import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;

/**
 * The Database class provides a layer between the JSON content retrieved from
 * the master database and the the local SQL database in which fingerprints are
 * cached.
 *
 * @author gmurphy
 */
public class Database {

    private Connection connection;
    private final String url; 
    private Log log;

    /**
     * Create a new database instance. The database content will be stored at
     * the specified location.
     *
     * @param db The location to store the database files.
     */
    public Database(String driver, String conn) {
        this(driver, conn, new SystemStreamLog());
    }

    /**
     * Create a new database instance. The database content will be stored at
     * the specified location. All errors will be reported on the supplied
     * logging mechanism.
     *
     * @param db
     * @param l
     */
    public Database(String driver, String conn, Log l) {

        log = l;
        connection = null;
        url = conn;
        try {
            //final String driver = "org.apache.derby.jdbc.EmbeddedDriver";
            Class.forName(driver).newInstance();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public Date latest() throws SQLException, VictimsException {
        
        try { 
            ResultSet rs = handle().createStatement().executeQuery(Query.GET_LATEST);
            if (rs.next()){
                return rs.getDate(1);
            }
        } finally {
            disconnect();
        }
       
        throw new VictimsException(Resources.ERR_INVALID_SQL_STATEMENT);
    }

    /**
     * Inserts a victims record into the database. 
     */
    public void insert(VictimsRecord r) throws SQLException {
         
        int changed;
        ResultSet rs = null;
        PreparedStatement stmt = null;
        try {
           
            /* victims table entries -----------------------------------------*/
            java.sql.Date created = new java.sql.Date(r.date.getTime());
        
            stmt = handle().prepareStatement(Query.INSERT_VICTIMS, Statement.RETURN_GENERATED_KEYS);
            //stmt.setInt   (1, r.id); autogenerated? 
            stmt.setString(1, Arrays.toString(r.cves));
            stmt.setString(2, r.vendor);
            stmt.setString(3, r.name);
            stmt.setDate  (4, created);
            stmt.setString(5, r.version);
            stmt.setString(6, r.submitter);
            stmt.setString(7, r.format);
            stmt.setString(8, r.status.name());
            
            stmt.executeUpdate();
            
            // set the automatically generated id
            if ((rs = stmt.getGeneratedKeys()) != null && rs.next())
                r.id = rs.getInt(1);
          
            stmt.close();
            
            /* fingerprint table entries -------------------------------------*/
            for (Map.Entry<String, HashRecord> hashes : r.hashes.entrySet()){
                
                String algorithm = hashes.getKey();
                HashRecord record = hashes.getValue();
                 
                changed = 0;
                for (Map.Entry<String, String> hash : record.files.entrySet()){
                    
                    stmt = handle().prepareStatement(Query.INSERT_FINGERPRINT); 
                    stmt.setInt(1, r.id);
                    stmt.setString(2,algorithm);
                    stmt.setString(3, record.combined);
                    stmt.setString(4, hash.getKey());   // filename
                    stmt.setString(5, hash.getValue()); //  hash  
                    changed+= stmt.executeUpdate();
                    stmt.close();
                }      
            }
            
            /* metadata table entries ---------------------------------------*/
            for (Map.Entry<String, Map<String, String> > meta : r.meta.entrySet()){
                
                String source = meta.getKey();
                Map<String, String> data = meta.getValue(); 
                
                changed = 0;
                for (String property : data.keySet()){
                    
                    stmt = handle().prepareStatement(Query.INSERT_METADATA);
                    stmt.setString(1, source);
                    stmt.setInt(2, r.id);
                    stmt.setString(3, property); 
                    stmt.setString(4, data.get(property));
                    
                    changed += stmt.executeUpdate();
                    stmt.close();            
                }
                            
            }          
            
        } finally { 
            if (stmt != null){
                stmt.close();
            }
        }
        
    }

    /**
     * Lists victims records within the database. 
     */
    public List<VictimsRecord> list() throws SQLException {
        
        List<VictimsRecord> records = new ArrayList<VictimsRecord>();
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            stmt = handle().createStatement();
            rs = stmt.executeQuery(Query.GET_ALL_VICTIMS_IDS);
            
            handle().setAutoCommit(false);
            while (rs.next()) 
                records.add(get(rs.getInt(1)));        
             
        } finally {
            if (rs != null)
                rs.close();
            
            if (stmt != null)
                stmt.close();
        
            handle().setAutoCommit(true);
        }
        
        return records;
    }
        
    /**
     * Retrieve a VictimsRecord from the database matching the supplied 
     * victims id.
     * 
     * TODO: Be better than this.. (pretty awful cyclonic complexity) 
     */
    public VictimsRecord get(int victimsId) throws SQLException {
        
        VictimsRecord record = null;
        PreparedStatement stmt = null;
        try {
            
            stmt = handle().prepareStatement(Query.GET_VICTIM_BY_ID);
            stmt.setInt(1, victimsId);
            
            /* victims data -------------------------------------------------*/
            ResultSet rs = stmt.executeQuery();
            if (! rs.next())
                return null;
                
            record = new VictimsRecord();
            record.id           = rs.getInt("id");
            record.cves         = rs.getString("cves").split(",");
            record.date         = rs.getDate("created");
            record.format       = rs.getString("format");
            record.name         = rs.getString("name");
            record.status       = Status.valueOf(rs.getString("status"));
            record.submitter    = rs.getString("submitter");
            record.vendor       = rs.getString("vendor");
            record.version      = rs.getString("version");
            
            stmt.close();
            
            /* fingerprint data ---------------------------------------------*/
            stmt = handle().prepareStatement(Query.GET_FINGERPRINT_ALGORITHMS);
            stmt.setInt(1, victimsId);
            rs = stmt.executeQuery();
            
            while (rs.next()){
                
                String algorithm = rs.getString("algorithm");
                HashRecord hashRecord = null;
                PreparedStatement hashes = null;
                ResultSet files = null;
                try {
                  
                    hashes = handle().prepareStatement(Query.GET_FINGERPRINT_FILES);
                    hashes.setInt(1, victimsId);
                    hashes.setString(2, algorithm);    
                    files = hashes.executeQuery();
                
                    boolean hasNext = files.next();
                    hashRecord = new HashRecord();
                    hashRecord.combined = files.getString("combined");

                    
                    while (hasNext){
                        
                        String filename = files.getString("filename");
                        String hash = files.getString("hash");
                        hashRecord.files.put(filename, hash);
                        
                        hasNext = files.next();
                    } 
                    
                } finally {
                    
                    if (hashes != null){
                        hashes.close();
                    }
                }
                
                if (hashRecord != null){
                    record.hashes.put(algorithm, hashRecord);
                }
                
            }            
            stmt.close();
            
            /* metadata -----------------------------------------------------*/
            stmt = handle().prepareStatement(Query.GET_METADATA_SOURCES);
            stmt.setInt(1, victimsId);
            rs = stmt.executeQuery();
                     
            while(rs.next()){
                
                ResultSet properties;
                PreparedStatement metadata;
                String source;
                
                source = rs.getString("source");
                metadata = handle().prepareStatement(Query.GET_METADATA_PROPERTIES);
                metadata.setInt(1, victimsId);
                metadata.setString (2, source);
                properties = metadata.executeQuery();
                
                Map<String, String> data = new HashMap<String, String>();
                while(properties.next()){
                    
                    String property = properties.getString("property");
                    String value    = properties.getString("value");
                    data.put(property, value);
                }

                record.meta.put(source, data);
            } 
                
        } finally {
            
            if (stmt != null)
                stmt.close();
        }

        return record;
    }
    
    /** 
     * Remove an entry from the database by victims id. 
     */
    public void remove(int victimsId) throws SQLException {
        
        PreparedStatement stmt = null;
        try {
            
            stmt = handle().prepareStatement(Query.DELETE_VICTIMS);
            stmt.setInt(1, victimsId);
            stmt.execute();
            
            stmt = handle().prepareStatement(Query.DELETE_FINGERPRINT);
            stmt.setInt(1, victimsId);
            stmt.execute();
            
            stmt = handle().prepareStatement(Query.DELETE_METADATA);
            stmt.setInt(1, victimsId);
            stmt.execute();
            
            
        } finally {
            if (stmt != null){
                stmt.close();
            }
        }
    }
    
 
    public void connect() throws SQLException {

        if (connection == null || connection.isClosed()){
            connection = DriverManager.getConnection(url);
        }
        
    }
 
    public void disconnect() throws SQLException {
        
        if (connection != null && ! connection.isClosed()){
            connection.close();
        }    
    }
    
     public Connection handle() throws SQLException {
        
        connect();
        return this.connection;
        
    }
    
    public boolean tableExists(String name) throws SQLException {
        
         DatabaseMetaData metadata = handle().getMetaData();
         return metadata.getTables(null, null, name, null).next();
    }
      
    public void createTables() throws SQLException {
        
        if (!tableExists("VICTIMS")) {
            handle().createStatement().execute(Query.CREATE_VICTIMS_TABLE);
        }

        if (!tableExists("FINGERPRINTS")) {
            handle().createStatement().execute(Query.CREATE_FINGERPRINT_TABLE);
        }

        if (!tableExists("METADATA")) {
            handle().createStatement().execute(Query.CREATE_METADATA_TABLE);
        }
            
    }
    
    public void dropTables() throws SQLException {
        

        if (tableExists("FINGERPRINTS")) {
            handle().createStatement().execute("DROP TABLE FINGERPRINTS");
        }

        if (tableExists("METADATA")) {
            handle().createStatement().execute("DROP TABLE METADATA");
        }

        if (tableExists("VICTIMS")) {
            handle().createStatement().execute("DROP TABLE VICTIMS");
        }

    }
    
    
    public void runSqlScript(File script) throws SQLException, IOException {

        String sql = IOUtils.slurp(script);
        for (String q : sql.split(";")) {
            handle().createStatement().execute(q);
        }
    }

    public VictimsRecord findByClassHash(String hash) throws SQLException {
        
        ResultSet rs;
        VictimsRecord record = null;
        PreparedStatement stmt = null;
        try {
            
            stmt = handle().prepareStatement(Query.FIND_BY_CLASS_HASH);
            stmt.setString(1, hash);
            
            rs = stmt.executeQuery();
            if (rs.next())
                record = get(rs.getInt(1));
            
        } finally {
            if (stmt != null)
                stmt.close();
                     
        }
        
        return record;
    }

    public VictimsRecord findByJarHash(String combined) throws SQLException {
        
        ResultSet rs;
        VictimsRecord record = null;
        PreparedStatement stmt = null;
        try {
            stmt = handle().prepareStatement(Query.FIND_BY_JAR_HASH);
            stmt.setString(1, combined);
            
            rs = stmt.executeQuery();
            if (rs.next())
                record = get(rs.getInt(1));
            
        } finally {
            if (stmt != null)
                stmt.close();
                     
        }
        
        return record;
    }
    
    /**
     * Extracts all victims records from the database that contain the list 
     * of class hashes within a certain tolerance. 
     * 
     * @param hashes
     * @param tolerance
     * @return
     * @throws SQLException 
     */
     public VictimsRecord[] findByClassSet(String[] hashes, double tolerance) throws SQLException {
        
        List<VictimsRecord> matches = new ArrayList<VictimsRecord>();
        long hits = Math.round(tolerance * hashes.length);
        StringBuilder q = new StringBuilder();
        
        // Build a query for matching classes 
        // FIXME: potential for injection / bad practice
        q.append("select victims_id from ( ");
        q.append(" select * from fingerprints where ");
        
        for (int i = 0; i < hashes.length; i++){
            q.append(" hash = '").append(hashes[i]);
            
            if (i < hashes.length - 1)
                q.append("' or ");  
            else
                q.append("' ");
        }
        
        q.append(") properties group by victims_id having count(victims_id) = ");
        q.append(String.valueOf(hits));
        
        ResultSet rs = null;
        PreparedStatement stmt = null;
        try {
            
            stmt = handle().prepareStatement(q.toString());
            rs = stmt.executeQuery();
            
            while (rs.next()){
                matches.add(get(rs.getInt(1)));
            }
            
        } finally {
            if (stmt != null)
                stmt.close();
        }
        
        return matches.toArray(new VictimsRecord[matches.size()]);
        
    }

    public VictimsRecord findByProperties(String group, String artifact, String version) throws SQLException {
        
        ResultSet rs; 
        VictimsRecord record = null;
        PreparedStatement stmt = null;
               
        try {
            stmt = handle().prepareStatement(Query.FIND_BY_POM_PROPERTIES);
            stmt.setString(1, group);
            stmt.setString(2, artifact);
            stmt.setString(3, version);
            
            rs = stmt.executeQuery();
            if (rs.next())
                record = get(rs.getInt(1));
            
            
        } finally {
            if (stmt != null)
                stmt.close();
        }
        
        return record;
    }

    public VictimsRecord findByImplementation(String vendor, String title, String version) throws SQLException {
        
        
        ResultSet rs; 
        VictimsRecord record = null;
        PreparedStatement stmt = null;
        
        try {
            
            stmt = handle().prepareStatement(Query.FIND_BY_POM_PROPERTIES);
            stmt.setString(1, vendor);
            stmt.setString(2, title);
            stmt.setString(3, version);
            
            rs = stmt.executeQuery();
            if (rs.next())
                record = get(rs.getInt(1));
            
        } finally { 
            if (stmt != null)
                stmt.close();
        }
        
        return record;
    }

   
}
