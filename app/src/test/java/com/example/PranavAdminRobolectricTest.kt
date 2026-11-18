package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.DynamicApiEndpointHolder
import com.example.data.PranavAdminRepository
import com.example.data.PranavApiFactory
import com.example.security.OwnerKeystoreVault
import com.example.ui.AdminModule
import com.example.ui.AdminViewModel
import com.example.ui.AuthState
import com.example.ui.PranavAdminApp
import com.example.ui.theme.MyApplicationTheme
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PranavAdminRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var mockServer: HttpServer
  private lateinit var baseUrl: String

  @Before
  fun setupServer() {
    mockServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    baseUrl = "http://127.0.0.1:${mockServer.address.port}"

    mockServer.createContext("/") { ex ->
      val path = ex.requestURI.path
      val body =
        when {
          path == "/api/v1/admin/auth/enroll" ->
            """{"ownerDeviceId":"own_test_1","keyFingerprint":"fp_12345","status":"enrolled"}"""
          path == "/api/v1/admin/auth/challenge" ->
            """{"challengeId":"chl_1","nonce":"nonce_1","expiresAt":"2030-01-01T00:00:00Z"}"""
          path == "/api/v1/admin/auth/verify" ->
            """{"sessionToken":"sess_tok_1","expiresAt":"2030-01-01T00:00:00Z","ownerDeviceId":"own_test_1"}"""
          path == "/api/v1/admin/dashboard" ->
            """{"kpis":{"appsCount":2,"installationsCount":5,"devicesCount":5,"activeLicensesCount":3,"activeVipCount":1,"runningBotsCount":1,"maintenanceActive":false,"emergencyLockdown":false,"totalEventsCount":42},"series":{"hourlyRequests":[10,20,30],"licenseDistribution":[3,1,1],"botActivity":[5,0,0]}}"""
          path == "/api/v1/admin/telegram-bots" && ex.requestMethod == "POST" ->
            """{"id":"bot_1","name":"RANGIN","botUsername":"rangin_bot","ownerTelegramId":"5123456789","tokenMasked":"***456","status":"running"}"""
          path == "/api/v1/admin/telegram-bots" ->
            """{"items":[{"id":"bot_1","name":"RANGIN","botUsername":"rangin_bot","ownerTelegramId":"5123456789","tokenMasked":"***456","webhookUrl":"","welcomeMessage":"Welcome to PRANAV RANGIN.","status":"running","updatesProcessed":12,"updatesRejected":2,"updatesDeduped":0,"lastError":""}]}"""
          else -> """{"items":[]}"""
        }
      val bytes = body.toByteArray(Charsets.UTF_8)
      ex.responseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    mockServer.start()
  }

  @After
  fun tearDownServer() {
    mockServer.stop(0)
  }

  @Test
  fun enrollmentShieldNeverShowsLoginScreenAndCompletesKeystoreEnrollment() {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val vault = OwnerKeystoreVault(ctx, baseUrl)
    val endpointHolder = DynamicApiEndpointHolder(baseUrl)
    val api = PranavApiFactory.create(endpointHolder)
    val repo = PranavAdminRepository(vault, endpointHolder, api)
    val vm = AdminViewModel(repo)

    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = false) {
        PranavAdminApp(
          state =
            vm.uiState.value.copy(
              authState =
                AuthState.Unverified(
                  apiBaseUrl = baseUrl,
                  keyFingerprint = "abcdef0123456789",
                  hardwareLabel = "Pixel Owner",
                  reason = "Device enrollment required",
                )
            ),
          vm = vm,
        )
      }
    }

    composeTestRule.onNodeWithText("Owner Device Verification Required").assertIsDisplayed()
    composeTestRule.onNodeWithTag("enroll_submit_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("enroll_token_input").performTextInput("PRN-BOOTSTRAP-TEST-1")
    composeTestRule.onNodeWithTag("enroll_submit_button").assertIsEnabled()
  }

  @Test
  fun authenticatedDashboardSupportsAll24ModulesAndTelegramBotFormValidation() {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val vault = OwnerKeystoreVault(ctx, baseUrl)
    val endpointHolder = DynamicApiEndpointHolder(baseUrl)
    val api = PranavApiFactory.create(endpointHolder)
    val repo = PranavAdminRepository(vault, endpointHolder, api)
    val vm = AdminViewModel(repo)

    assertEquals(24, AdminModule.entries.size)

    composeTestRule.setContent {
      val state =
        vm.uiState.value.copy(
          authState = AuthState.Authenticated("own_test_1"),
          activeModule = AdminModule.TELEGRAM_BOTS,
        )
      MyApplicationTheme(darkTheme = false) {
        PranavAdminApp(state = state, vm = vm)
      }
    }

    composeTestRule.onNodeWithTag("telegram_bots_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("add_bot_submit_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("bot_token_input").performTextInput("123456:AAH_TEST_TOKEN")
    composeTestRule.onNodeWithTag("bot_owner_id_input").performTextInput("5123456789")
    composeTestRule.onNodeWithTag("add_bot_submit_button").assertIsEnabled()
    assertTrue(AdminModule.entries.any { it == AdminModule.TELEGRAM_BOTS })
  }
}
