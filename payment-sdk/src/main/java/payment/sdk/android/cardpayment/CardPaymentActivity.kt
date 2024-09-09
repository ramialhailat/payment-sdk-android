package payment.sdk.android.cardpayment

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wallet.AutoResolveHelper
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.WalletConstants
import com.google.android.gms.wallet.button.ButtonConstants
import com.google.android.gms.wallet.button.ButtonOptions
import com.google.android.gms.wallet.button.PayButton
import com.google.android.gms.wallet.contract.TaskResultContracts.GetPaymentDataResult
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import payment.sdk.android.SDKConfig
import payment.sdk.android.cardpayment.partialAuth.model.PartialAuthActivityArgs
import payment.sdk.android.cardpayment.threedsecure.ThreeDSecureRequest
import payment.sdk.android.cardpayment.threedsecure.ThreeDSecureWebViewActivity
import payment.sdk.android.cardpayment.threedsecuretwo.webview.PartialAuthIntent
import payment.sdk.android.cardpayment.threedsecuretwo.webview.ThreeDSecureTwoWebViewActivity
import payment.sdk.android.cardpayment.threedsecuretwo.webview.ThreeDSecureTwoWebViewActivity.Companion.INTENT_CHALLENGE_RESPONSE
import payment.sdk.android.cardpayment.threedsecuretwo.webview.ThreeDSecureTwoWebViewActivity.Companion.STATUS_AWAITING_PARTIAL_AUTH_APPROVAL
import payment.sdk.android.cardpayment.visaInstalments.model.NewCardDto
import payment.sdk.android.cardpayment.visaInstalments.model.VisaInstalmentActivityArgs
import payment.sdk.android.core.OrderAmount
import payment.sdk.android.core.VisaPlans
import payment.sdk.android.core.api.CoroutinesGatewayHttpClient
import payment.sdk.android.core.dependency.StringResourcesImpl
import payment.sdk.android.googlepay.ButtonTheme
import payment.sdk.android.googlepay.GooglePayVMState
import payment.sdk.android.googlepay.GooglePayViewModel
import payment.sdk.android.sdk.R


class CardPaymentActivity : AppCompatActivity(), CardPaymentContract.Interactions {

    private lateinit var presenter: CardPaymentContract.Presenter

    private val viewModel: GooglePayViewModel by viewModels { GooglePayViewModel.Factory }

    private val paymentDataLauncher = registerForActivityResult(GetPaymentDataResult()) { taskResult ->
        when (taskResult.status.statusCode) {
            CommonStatusCodes.SUCCESS -> {
                taskResult.result!!.let {
                    Log.i("Google Pay result:", it.toJson())
                }
            }
            CommonStatusCodes.CANCELED -> {
                Log.i("Google Pay result:", "CANCELED")
            }
            AutoResolveHelper.RESULT_ERROR -> {
                Log.i("Google Pay result:", "RESULT_ERROR")
            }
            CommonStatusCodes.INTERNAL_ERROR -> {
                Log.i("Google Pay result:", "INTERNAL_ERROR")
            }
        }
    }

    private val partialAuthActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            finishWithData(CardPaymentData.getFromIntent(result.data!!))
        } else {
            onPaymentFailed()
        }
    }

    fun createPaymentsClient(context: Context): PaymentsClient {
        val walletOptions = Wallet.WalletOptions.Builder()
            .setEnvironment(WalletConstants.ENVIRONMENT_TEST)
            .build()

        return Wallet.getPaymentsClient(context, walletOptions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_payment)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (SDKConfig.showCancelAlert) {
                    showDialog()
                } else {
                    setResult(RESULT_CANCELED, intent)
                    finish()
                }
            }
        })

        val googlePayButton = findViewById<PayButton>(R.id.google_pay_button)
        val client = createPaymentsClient(this)
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (state is GooglePayVMState.Submit) {
                    googlePayButton.initialize(
                        ButtonOptions.newBuilder()
                            .setButtonTheme(ButtonTheme.Dark.value)
                            .setButtonType(ButtonConstants.ButtonType.PAY)
                            .setCornerRadius(32)
                            .setAllowedPaymentMethods(state.allowedPaymentMethods)
                            .build()
                    )
                    googlePayButton.setOnClickListener {
                        val task = client.loadPaymentData(state.paymentDataRequest)
                        task.addOnCompleteListener(paymentDataLauncher::launch)
                    }
                }
            }
        }
        setToolBar()
        val url = intent.getStringExtra(URL_KEY)
        val code = intent.getStringExtra(CODE)

        if(url == null || code == null) {
            Log.e("CardPaymentActivity", "url and code missing")
            onGenericError("url and code missing")
            return
        }

        presenter = CardPaymentPresenter(
            url = url,
            code = code,
            view = CardPaymentView(findViewById(R.id.bottom_sheet)),
            interactions = this,
            paymentApiInteractor = CardPaymentApiInteractor(CoroutinesGatewayHttpClient()),
            stringResources = StringResourcesImpl(this)
        )
        presenter.init()
    }

    override fun onStart3dSecure(threeDSecureRequest: ThreeDSecureRequest) {
        startActivityForResult(
            ThreeDSecureWebViewActivity.getIntent(
                context = this,
                acsUrl = threeDSecureRequest.acsUrl,
                acsPaReq = threeDSecureRequest.acsPaReq,
                acsMd = threeDSecureRequest.acsMd,
                gatewayUrl = threeDSecureRequest.gatewayUrl
            ),
            THREE_D_SECURE_REQUEST_KEY
        )
    }

    override fun onStart3dSecureTwo(
        threeDSecureRequest: ThreeDSecureRequest,
        directoryServerID: String, threeDSMessageVersion: String,
        paymentCookie: String, threeDSTwoAuthenticationURL: String,
        threeDSTwoChallengeResponseURL: String, outletRef: String,
        orderRef: String, orderUrl: String, paymentRef: String
    ) {
        startActivityForResult(
            ThreeDSecureTwoWebViewActivity.getIntent(
                context = this,
                threeDSMethodData = threeDSecureRequest.threeDSTwo?.threeDSMethodData,
                threeDSMethodNotificationURL = threeDSecureRequest.threeDSTwo?.threeDSMethodNotificationURL,
                threeDSMethodURL = threeDSecureRequest.threeDSTwo?.threeDSMethodURL,
                threeDSServerTransID = threeDSecureRequest.threeDSTwo?.threeDSServerTransID,
                paymentCookie = paymentCookie,
                threeDSAuthenticationsUrl = threeDSTwoAuthenticationURL,
                directoryServerID = directoryServerID,
                threeDSMessageVersion = threeDSMessageVersion,
                threeDSTwoChallengeResponseURL = threeDSTwoChallengeResponseURL,
                outletRef = outletRef,
                orderRef = orderRef,
                orderUrl = orderUrl,
                paymentRef = paymentRef
            ),
            THREE_D_SECURE_TWO_REQUEST_KEY
        )
    }

    override fun onPaymentAuthorized() {
        finishWithData(CardPaymentData(CardPaymentData.STATUS_PAYMENT_AUTHORIZED))
    }

    override fun onPaymentPurchased() {
        finishWithData(CardPaymentData(CardPaymentData.STATUS_PAYMENT_PURCHASED))
    }

    override fun onPaymentCaptured() {
        finishWithData(CardPaymentData(CardPaymentData.STATUS_PAYMENT_CAPTURED))
    }

    override fun onAuthorized(accessToken: String, amount: Double, currencyCode: String) {
        viewModel.getGooglePayConfig(accessToken, currencyCode, amount)
    }

    override fun onPaymentFailed() {
        finishWithData(CardPaymentData(CardPaymentData.STATUS_PAYMENT_FAILED))
    }

    override fun onGenericError(message: String?) {
        finishWithData(CardPaymentData(CardPaymentData.STATUS_GENERIC_ERROR, message))
    }

    override fun onPaymentPostAuthReview() {
        finishWithData(CardPaymentData(CardPaymentData.STATUS_POST_AUTH_REVIEW))
    }

    override fun launchVisaInstalment(
        visaPlans: VisaPlans,
        paymentCookie: String,
        paymentUrl: String,
        payPageUrl: String,
        orderUrl: String,
        newCardDto: NewCardDto,
        orderAmount: OrderAmount
    ) {
        startActivityForResult(
            VisaInstalmentActivityArgs.getArgs(
                paymentCookie = paymentCookie,
                savedCardUrl = null,
                visaPlans = visaPlans,
                paymentUrl = paymentUrl,
                newCard = newCardDto,
                payPageUrl = payPageUrl,
                savedCard = null,
                orderUrl = orderUrl,
                orderAmount = orderAmount,
                accessToken = paymentCookie
            ).toIntent(this),
            VISA_INSTALMENT_SELECTION_KEY
        )
    }

    override fun onPartialAuth(partialAuthIntent: PartialAuthIntent) {
        try {
            partialAuthActivityLauncher.launch(PartialAuthActivityArgs.getArgs(partialAuthIntent).toIntent(this))
        } catch (e: IllegalArgumentException) {
            finishWithData(CardPaymentData(CardPaymentData.STATUS_GENERIC_ERROR, e.message))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == THREE_D_SECURE_REQUEST_KEY ||
            requestCode == THREE_D_SECURE_TWO_REQUEST_KEY
        ) {
            if (resultCode == RESULT_OK && data != null) {
                val state = data.getStringExtra(ThreeDSecureWebViewActivity.KEY_3DS_STATE)
                if (state != null) {
                    if (state == STATUS_AWAITING_PARTIAL_AUTH_APPROVAL) {
                        val intent = data.getParcelableExtra(INTENT_CHALLENGE_RESPONSE) as? PartialAuthIntent
                        if (intent != null) {
                            onPartialAuth(intent)
                        } else {
                            onPaymentFailed()
                        }
                    } else {
                        presenter.onHandle3DSecurePaymentSate(data.getStringExtra(ThreeDSecureWebViewActivity.KEY_3DS_STATE)!!)
                    }
                }

            } else {
                onPaymentFailed()
            }
        }

        if (requestCode == VISA_INSTALMENT_SELECTION_KEY) {
            if (resultCode == RESULT_OK && data != null) {
                finishWithData(CardPaymentData.getFromIntent(data))
            } else {
                onPaymentFailed()
            }
        }
    }

    private fun setToolBar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar3)
        toolbar.setNavigationIcon(R.drawable.ic_back_button)
        toolbar.setNavigationOnClickListener {
            if (SDKConfig.showCancelAlert) {
                showDialog()
            } else {
                setResult(RESULT_CANCELED, intent)
                finish()
            }
        }
        toolbar.setTitle(R.string.make_payment)
        toolbar.setTitleTextColor(Color.WHITE)
    }


    private fun finishWithData(cardPaymentData: CardPaymentData) {
        val intent = Intent().apply {
            putExtra(CardPaymentData.INTENT_DATA_KEY, cardPaymentData)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    /**
     * Window exit animation (@link {Animation.Design.BottomSheetDialog}) does not seem to work for
     * devices below 21.
     */
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            overridePendingTransition(0, 0)

        }
    }

    private fun showDialog() {
        with(AlertDialog.Builder(this)) {
            setMessage(R.string.cancel_payment_alert_message)
            setTitle(R.string.cancel_payment_alert_title)
            setCancelable(false)
            setPositiveButton(R.string.confirm_cancel_alert) { _: DialogInterface?, _: Int ->
                setResult(RESULT_CANCELED, intent)
                finish()
            }
            setNegativeButton(R.string.cancel_alert ) { dialog: DialogInterface, _: Int ->
                dialog.cancel()
            }
            show()
        }
    }

    companion object {

        private const val THREE_D_SECURE_REQUEST_KEY: Int = 100
        private const val THREE_D_SECURE_TWO_REQUEST_KEY: Int = 110
        private const val VISA_INSTALMENT_SELECTION_KEY: Int = 120

        private const val URL_KEY = "gateway-payment-url"
        private const val CODE = "code"


        fun getIntent(context: Context, url: String, code: String) =
            Intent(context, CardPaymentActivity::class.java).apply {
                putExtra(URL_KEY, url)
                putExtra(CODE, code)
            }
    }
}
