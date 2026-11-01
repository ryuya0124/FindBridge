package dev.findbridge.android.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

internal object UpdateScheduler {
    const val CHECK_JOB = 0x16fb0201
    const val INSTALL_JOB = 0x16fb0202

    fun configure(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (!UpdateRepository.get(context).automatic) {
            jobs.cancel(CHECK_JOB)
            jobs.cancel(INSTALL_JOB)
            return
        }
        val hours = UpdateRepository.get(context).intervalHours
        if (hours == 0) {
            jobs.cancel(CHECK_JOB)
            return
        }
        if (
            jobs.getPendingJob(CHECK_JOB)?.intervalMillis != TimeUnit.HOURS.toMillis(hours.toLong())
        ) {
            jobs.schedule(
                JobInfo.Builder(CHECK_JOB, ComponentName(context, UpdateJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(
                        TimeUnit.HOURS.toMillis(hours.toLong()),
                        TimeUnit.HOURS.toMillis(1),
                    )
                    .setPersisted(true)
                    .build()
            )
        }
    }

    fun whenIdle(context: Context) {
        val repository = UpdateRepository.get(context)
        if (!repository.automatic || repository.state.phase != UpdatePhase.READY) return
        context
            .getSystemService(JobScheduler::class.java)
            .schedule(
                JobInfo.Builder(INSTALL_JOB, ComponentName(context, UpdateJobService::class.java))
                    .build()
            )
    }
}

class UpdateJobService : JobService() {
    private val stopped =
        java.util.concurrent.ConcurrentHashMap<
            JobParameters,
            java.util.concurrent.atomic.AtomicBoolean,
        >()

    override fun onStartJob(params: JobParameters): Boolean {
        val updates = UpdateRepository.get(this)
        if (!updates.automatic) return false
        val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
        stopped[params] = canceled
        val allowed = { !canceled.get() && updates.automatic }
        val completed = {
            stopped.remove(params)
            if (!canceled.get()) jobFinished(params, false)
        }
        if (params.jobId == UpdateScheduler.INSTALL_JOB) updates.installWhenIdle(allowed, completed)
        else updates.check(force = true, allowed = allowed, completed = completed)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped.remove(params)?.set(true)
        return UpdateRepository.get(this).automatic
    }
}
