package com.calm.launcher;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Fetches a few new wallpapers once a day (and once right away when the cache is empty). */
public class WallpaperJob extends JobService {
    private static final int ID_DAILY = 1;
    private static final int ID_NOW = 2;
    private static final int PER_DAY = 4;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        ComponentName cn = new ComponentName(c, WallpaperJob.class);
        if (js.getPendingJob(ID_DAILY) == null) {
            js.schedule(new JobInfo.Builder(ID_DAILY, cn)
                    .setPeriodic(24L * 60 * 60 * 1000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        }
        String[] cached = Wallpapers.dir(c).list((d, n) -> n.endsWith(".jpg"));
        if ((cached == null || cached.length == 0) && js.getPendingJob(ID_NOW) == null) {
            js.schedule(new JobInfo.Builder(ID_NOW, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(5000)
                    .setBackoffCriteria(10 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = Wallpapers.fetch(getApplicationContext(), PER_DAY) != -1; // -1: rate limited, retry later
            } finally {
                jobFinished(params, !ok && params.getJobId() == ID_NOW);
            }
        }, "calm-wallpapers").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
