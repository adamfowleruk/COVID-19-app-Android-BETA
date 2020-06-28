/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

package uk.nhs.nhsx.sonar.android.app.registration

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import timber.log.Timber
import javax.inject.Inject

class RegistrationWork @Inject constructor(
    private val registrationUseCase: RegistrationUseCase,
    private val dummyRegistrationUseCase: DummyRegistrationUseCase
) {

    suspend fun doWork(): ListenableWorker.Result {
        //val result = registrationUseCase.register()
        // TODO make this conditional
        val result = dummyRegistrationUseCase.register()
        Timber.tag("RegistrationUseCase").d("doWork result = $result")

        return when (result) {
            RegistrationResult.Success -> ListenableWorker.Result.success()
            RegistrationResult.Error -> ListenableWorker.Result.retry()
            RegistrationResult.WaitingForActivationCode -> {
                val outputData =
                    workDataOf(RegistrationWorker.WAITING_FOR_ACTIVATION_CODE to true)
                ListenableWorker.Result.success(outputData)
            }
        }
    }
}
