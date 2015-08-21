package com.nanodegree.bpham.popularmovies.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.nanodegree.bpham.popularmovies.R;
import com.nanodegree.bpham.popularmovies.Utility;
import com.nanodegree.bpham.popularmovies.data.MovieContract;
import com.nanodegree.bpham.popularmovies.tmdbAPI.Discovery;
import com.nanodegree.bpham.popularmovies.tmdbAPI.Reviews;
import com.nanodegree.bpham.popularmovies.tmdbAPI.TMDBService;
import com.nanodegree.bpham.popularmovies.tmdbAPI.Trailers;

import java.util.Vector;

import retrofit.RestAdapter;

/**
 * Created by binh on 8/19/15.
 *
 */

public class MovieSyncAdapter extends AbstractThreadedSyncAdapter {
    private final String LOG_TAG = MovieSyncAdapter.class.getSimpleName();

    private static final int SYNC_HOURS = 4;
    private static final int SYNC_INTERVAL = 60 * 60 * SYNC_HOURS;
    private static final int SYNC_FLEXTIME = SYNC_INTERVAL / SYNC_HOURS;

    private final String BASE_URL = "http://api.themoviedb.org/3";
    private final String API_KEY = "";

    public MovieSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    public static Account getSyncAccount(Context context) {
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        // Create the account type and default account
        Account newAccount = new Account(
                context.getString(R.string.app_name), context.getString(R.string.sync_account_type));

        // If the password doesn't exist, the account doesn't exist
        if (null == accountManager.getPassword(newAccount)) {

        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
            if (!accountManager.addAccountExplicitly(newAccount, "", null)) {
                return null;
            }
            /*
             * If you don't set android:syncable="true" in
             * in your <provider> element in the manifest,
             * then call ContentResolver.setIsSyncable(account, AUTHORITY, 1)
             * here.
             */

            onAccountCreated(newAccount, context);
        }
        return newAccount;
    }

    private static void onAccountCreated(Account newAccount, Context context) {
        MovieSyncAdapter.configurePeriodicSync(context, SYNC_INTERVAL, SYNC_FLEXTIME);

        ContentResolver.setSyncAutomatically(newAccount, context.getString(R.string.content_authority), true);

        syncImmediately(context);
    }

    public static void initializeSyncAdapter(Context context) {
        getSyncAccount(context);
    }

    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        Account account = getSyncAccount(context);
        String authority = context.getString(R.string.content_authority);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            SyncRequest request = new SyncRequest.Builder().
                    syncPeriodic(syncInterval, flexTime).
                    setSyncAdapter(account, authority).
                    setExtras(new Bundle()).build();
            ContentResolver.requestSync(request);
        } else {
            ContentResolver.addPeriodicSync(account,
                    authority, new Bundle(), syncInterval);
        }
    }

    public static void syncImmediately(Context context) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(getSyncAccount(context),
                context.getString(R.string.content_authority), bundle);
    }

    @Override
    public void onPerformSync(Account account, Bundle bundle, String s,
                              ContentProviderClient client, SyncResult syncResult) {
        Context context = getContext();
        ContentResolver resolver = context.getContentResolver();
        // get movies
        String sortPref = Utility.getPreferenceSortBy(context);

        RestAdapter restAdapter = new RestAdapter.Builder()
                .setEndpoint(BASE_URL)
                .build();
        TMDBService service = restAdapter.create(TMDBService.class);
        String sortBy = "";
        if (sortPref.equals(context.getString(R.string.pref_sorting_popularity))) {
            sortBy = "popularity.desc";
        } else if (sortPref.equals(context.getString(R.string.pref_sorting_rating))) {
            sortBy = "vote_average.desc";
        }
        Discovery discovery = service.discoverMovies(API_KEY, sortBy);

        ContentValues updateValues = new ContentValues();
        updateValues.put(MovieContract.MovieEntry.COLUMN_POSITION, -1);
        resolver.update(MovieContract.MovieEntry.CONTENT_URI,
                updateValues,
                null,
                null);
        insertMovieFromDiscovery(service, discovery);

        //remove movies that is not needed (position = -1 and not favorite)
        String selection = MovieContract.MovieEntry.COLUMN_FAVORITE + "=0 AND " +
                MovieContract.MovieEntry.COLUMN_POSITION + "=-1";
        String[] projection = {MovieContract.MovieEntry.COLUMN_TMDB_ID};

        Cursor moviesToDelete = resolver.query(MovieContract.MovieEntry.CONTENT_URI,
                projection,
                selection,
                null,
                null);

        resolver.delete(MovieContract.MovieEntry.CONTENT_URI,
                selection,
                null);
        while (moviesToDelete.moveToNext()) {
            resolver.delete(MovieContract.TrailerEntry.CONTENT_URI,
                    MovieContract.TrailerEntry.COLUMN_MOVIE_KEY + "=?",
                    new String[]{moviesToDelete.getString(0)});
            resolver.delete(MovieContract.ReviewEntry.CONTENT_URI,
                    MovieContract.ReviewEntry.COLUMN_MOVIE_KEY + "=?",
                    new String[]{moviesToDelete.getString(0)});
        }
    }

    private void insertMovieFromDiscovery(TMDBService service, Discovery discovery) {
        for (int i = 0; i < discovery.getResults().size(); i++) {
            Discovery.Result result = discovery.getResults().get(i);
            if (result.getPosterPath()==null)
                continue;
            ContentValues values = new ContentValues();
            values.put(MovieContract.MovieEntry.COLUMN_TMDB_ID, result.getId());
            values.put(MovieContract.MovieEntry.COLUMN_TITLE, result.getTitle());
            values.put(MovieContract.MovieEntry.COLUMN_POSTER, result.getPosterPath());
            values.put(MovieContract.MovieEntry.COLUMN_SYNOPSIS, result.getOverview());
            values.put(MovieContract.MovieEntry.COLUMN_VOTE_AVERAGE, result.getVoteAverage());
            values.put(MovieContract.MovieEntry.COLUMN_RELEASE_DATE, result.getReleaseDate());
            values.put(MovieContract.MovieEntry.COLUMN_POSITION, i);
            Uri uri = getContext().getContentResolver().insert(MovieContract.MovieEntry.CONTENT_URI,
                    values);
            int id = MovieContract.MovieEntry.getIdFromUri(uri);
            getMoviesTrailers(service, id);
            getMoviesReviews(service, id);
        }
    }

    private void getMoviesTrailers(TMDBService service, int movieId) {
        Trailers trailers = service.getTrailers(movieId, API_KEY);
        Vector<ContentValues> valuesVector = new Vector<>(trailers.getResults().size());
        for (int i = 0; i < trailers.getResults().size(); i++) {
            Trailers.Result result = trailers.getResults().get(i);
            ContentValues values = new ContentValues();
            values.put(MovieContract.TrailerEntry.COLUMN_TMDB_ID, result.getId());
            values.put(MovieContract.TrailerEntry.COLUMN_MOVIE_KEY, movieId);
            values.put(MovieContract.TrailerEntry.COLUMN_KEY, result.getKey());
            values.put(MovieContract.TrailerEntry.COLUMN_NAME, result.getName());
            values.put(MovieContract.TrailerEntry.COLUMN_SITE, result.getSite());

            valuesVector.add(values);
        }
        ContentValues[] values = new ContentValues[valuesVector.size()];
        valuesVector.toArray(values);
        getContext().getContentResolver().bulkInsert(MovieContract.TrailerEntry.CONTENT_URI,
                values);
    }

    private void getMoviesReviews(TMDBService service, int movieId) {
        Reviews reviews = service.getReviews(movieId, API_KEY);
        Vector<ContentValues> valuesVector = new Vector<>(reviews.getResults().size());
        for (int i = 0; i < reviews.getResults().size(); i++) {
            Reviews.Result result = reviews.getResults().get(i);
            ContentValues values = new ContentValues();
            values.put(MovieContract.ReviewEntry.COLUMN_TMDB_ID, result.getId());
            values.put(MovieContract.ReviewEntry.COLUMN_MOVIE_KEY, movieId);
            values.put(MovieContract.ReviewEntry.COLUMN_AUTHOR, result.getAuthor());
            values.put(MovieContract.ReviewEntry.COLUMN_CONTENT, result.getContent());
            valuesVector.add(values);
        }
        ContentValues[] values = new ContentValues[valuesVector.size()];
        valuesVector.toArray(values);
        getContext().getContentResolver().bulkInsert(MovieContract.ReviewEntry.CONTENT_URI,
                values);
    }
}
