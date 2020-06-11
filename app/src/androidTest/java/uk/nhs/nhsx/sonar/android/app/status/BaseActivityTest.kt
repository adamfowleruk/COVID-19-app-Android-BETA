package uk.nhs.nhsx.sonar.android.app.status

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Test
import uk.nhs.nhsx.sonar.android.app.EspressoTest
import uk.nhs.nhsx.sonar.android.app.R
import uk.nhs.nhsx.sonar.android.app.edgecases.EdgeCaseRobot
import uk.nhs.nhsx.sonar.android.app.startTestActivity

class BaseActivityTest: EspressoTest() {

    private val statusRobot = StatusRobot()
    private val edgeCaseRobot = EdgeCaseRobot()

    private fun startActivity() {
        testAppContext.setFullValidUser(DefaultState)
        testAppContext.app.startTestActivity<StatusActivity>()
    }

    @Test
    fun testResumeWhenBluetoothIsDisabled() {
        startActivity()

        triggerResumeAfter {
            testAppContext.ensureBluetoothDisabled()
        }

        edgeCaseRobot.checkTitle(R.string.re_enable_bluetooth_title)
        edgeCaseRobot.clickTakeAction()

        // TODO: fix flaky check
        statusRobot.checkActivityIsDisplayed(DefaultState::class)
    }

    @Test
    fun testResumeWhenLocationAccessIsDisabled() {
        startActivity()

        testAppContext.disableLocationAccess()
        edgeCaseRobot.checkTitle(R.string.re_enable_location_title)

        testAppContext.enableLocationAccess()
        statusRobot.checkActivityIsDisplayed(DefaultState::class)
    }

    @Test
    fun testResumeWhenLocationPermissionIsRevoked() {
        startActivity()

        triggerResumeAfter {
            testAppContext.revokeLocationPermission()
        }

        edgeCaseRobot.checkTitle(R.string.re_allow_location_permission_title)
        edgeCaseRobot.clickTakeAction()

        testAppContext.device.wait(Until.gone(By.text("Allow this app to access your location to continue")), 500)
        testAppContext.grantLocationPermission()
        testAppContext.device.pressBack()

        statusRobot.checkActivityIsDisplayed(DefaultState::class)
    }

    private fun triggerResumeAfter(function: () -> Unit) {
        statusRobot.clickCurrentAdviceCard()
        function()
        testAppContext.device.pressBack()
    }
}
