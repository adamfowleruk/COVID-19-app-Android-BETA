package uk.nhs.nhsx.sonar.android.app.registration;
/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

import com.android.volley.ClientError
import timber.log.Timber
import uk.nhs.nhsx.sonar.android.app.di.module.AppModule
import uk.nhs.nhsx.sonar.android.app.http.KeyStorage
import uk.nhs.nhsx.sonar.android.app.onboarding.PostCodeProvider
import java.util.*
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DummyRegistrationUseCase @Inject constructor(
    private val tokenRetriever: TokenRetriever,
    private val residentApi: ResidentApi,
    private val sonarIdProvider: SonarIdProvider,
    private val keyStorage: KeyStorage,
    private val postCodeProvider: PostCodeProvider,
    private val activationCodeProvider: ActivationCodeProvider,
    @Named(AppModule.DEVICE_MODEL) private val deviceModel: String,
    @Named(AppModule.DEVICE_OS_VERSION) private val deviceOsVersion: String
) {

    suspend fun register(): RegistrationResult {
        try {
            if (sonarIdProvider.hasProperSonarId()) {
                Timber.d("Already registered")
                return RegistrationResult.Success
            }


            val firebaseToken = getFirebaseToken()

            val sonarId = UUID.randomUUID().toString()
            Timber.d("sonarId = $sonarId")
            storeSonarId(sonarId)
            Timber.d("sonarId stored")


            // base64 of ec public key
            keyStorage.storeServerPublicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExVLKLgGfvaoAG5cUpzYGnDjiFD/X0/VfrWZWBfdzgYRVNI1SU/yW/SclmXWKNzo79ujC1Yifiv6n6uFjIumGoA==")
            // base64 of symmetric key
            keyStorage.storeSecretKey("G+ltoMsL6Qj4H3hp5GKupgodI2QANylBz72aNWnGCNE=")
            //keyStorage.storeSecretKey("NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1NjcyM2I3ZTUtNDY3Yy00ZDQ2LTg3YTMtYjMwZjc0ZDg2MzE1")
            Registration(sonarId)


            return RegistrationResult.Success
        } catch (e: ClientError) {
            // TODO: delete firebase token?
            activationCodeProvider.clear()
            return RegistrationResult.Error
        } catch (e: Exception) {
            Timber.e(e, "RegistrationUseCase exception")
            return RegistrationResult.Error
        }
    }

    private suspend fun getFirebaseToken(): Token =
    try {
        tokenRetriever.retrieveToken()
    } catch (e: Exception) {
        throw e
    }

    private suspend fun registerDevice(firebaseToken: String) =
    residentApi
            .register(firebaseToken)
            .toCoroutineUnsafe()

    private suspend fun registerResident(
            activationCode: String,
            firebaseToken: String,
            postCode: String
    ): String {
        val confirmation = DeviceConfirmation(
                activationCode = activationCode,
                pushToken = firebaseToken,
                deviceModel = deviceModel,
                deviceOsVersion = deviceOsVersion,
                postalCode = postCode
        )

        return residentApi
                .confirmDevice(confirmation)
                .map { it.id }
            .toCoroutineUnsafe()
    }

    private fun storeSonarId(sonarId: String) {
        sonarIdProvider.set(sonarId)
    }
}
