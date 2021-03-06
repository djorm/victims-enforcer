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
package com.redhat.victims;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.redhat.victims.db.Database;
import com.redhat.victims.db.VictimsRecord;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;


/**
 * The VictimClient class provides the mechanism for synchronizing data within a
 * local Victims database with the remote canonical server.
 *
 * @author gmurphy
 */
public class Synchronizer {

    private String baseURL;
    private Log log;
    private DateFormat fmt;

    /**
     * Creates a new synchronizer instance that will attempt to
     * retrieve updates from the supplied URL.
     * @param url The url to synchronize to.
     */
    public Synchronizer(String url) {
        this(url, new SystemStreamLog());
    }

    /**
     * Creates a new synchronizer instance that will attempt to
     * retreive updates from the supplied URL.
     * @param url The url to synchronize to.
     * @param log The log to send messages to.
     */
    public Synchronizer(String url, Log l) {
        baseURL = url;
        log = l;
        fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    private String getUpdateURL(final Date lastUpdated) {
        String dateString = fmt.format(lastUpdated);
        return String.format("%s/update/%s/", baseURL, dateString);
    }

    private String getObseleteURL(final Date lastUpdated) {
        String dateString = fmt.format(lastUpdated);
        return String.format("%s/remove/%s/", baseURL, dateString);
    }

//    private String progressString(int done, int total){
//
//        int bars, i;
//        double completed,  kb;
//        String percent, progress;
//        StringBuffer sb;
//
//        completed = (double) done / (double) total;
//        sb = new StringBuffer();
//        sb.append("[");
//
//        bars = (int)(completed * 10);
//        for (i = 0; i < bars; i++){
//            sb.append("#");
//        }
//
//        for (i = bars; i < 10; i++){
//            sb.append(" ");
//        }
//        sb.append(" ]");
//
//        kb = ((double)total / 1024);
//        percent = new DecimalFormat(" ###.00%").format(completed);
//        progress = new DecimalFormat("###.## kb").format(kb);
//
//        sb.append(percent);
//        sb.append(" of ");
//        sb.append(progress);
//
//        return sb.toString();
//    }

    /**
     * Actual synchronization mechanism abstraction as essentially does the same
     * thing for update / delete.
     *
     * @param db The database to update.
     * @param q  The statement (update / insert) to perform on the database.
     * @param url The url to make the request to.
     * @return The number of entries that were updated.
     * @throws Exception Thrown if a bad response is received from the server.
     */
    private int sync(Database db, final String url) throws Exception {

        int modified = 0;
        HttpMethod get = new GetMethod(url);
        HttpClient client = new HttpClient();
        client.executeMethod(get);

        Gson gson = new GsonBuilder().setDateFormat(VictimsRecord.DATE_FORMAT).create();
        InputStream response = get.getResponseBodyAsStream();
        JsonReader json = new JsonReader(new InputStreamReader(response, "UTF-8"));
        json.beginArray();
        while(json.hasNext()){

            json.beginObject();
            json.nextName(); // discard fields
            VictimsRecord v = gson.fromJson(json, VictimsRecord.class);
            db.insert(v);
            modified++;
            json.endObject();
        }
        json.endArray();

        return modified;
    }

    /**
     * The supplied database is updated and synchronized to contain the latest
     * content available from the Red Hat Security Response Team.
     *
     * @param db The database to be updated
     * @throws VictimsException If the synchronization operation failed.
     */
    public void synchronizeDatabase(Database db) throws VictimsException {

        try {

            int changes;

            Date newestEntry = db.lastUpdated();
            if (newestEntry == null)
                newestEntry = new Date(0); // All entries.

            log.info(IOUtils.fmt(Resources.INFO_DATABASE_LAST_UPDATE, newestEntry.toString()));
            log.info(IOUtils.fmt(Resources.INFO_PERFORMING_SYNC));

            changes = sync(db, getUpdateURL(newestEntry));
            log.info(String.format(IOUtils.fmt(Resources.INFO_ITEMS_ADDED, changes)));

            changes = sync(db, getObseleteURL(newestEntry));
            log.info(String.format(IOUtils.fmt(Resources.INFO_ITEMS_REMOVED, changes)));

            newestEntry = db.lastUpdated();
            if (newestEntry != null)
                log.info(IOUtils.fmt(Resources.INFO_DATABASE_LAST_UPDATE, newestEntry.toString()));


        } catch(Exception e){
            e.printStackTrace();
            throw new VictimsException(IOUtils.fmt(Resources.ERR_SYNCHRONIZATION_FAILURE), e);

//        } finally {
//            try { db.disconnect(); } catch(SQLException e){};
        }

    }
}
