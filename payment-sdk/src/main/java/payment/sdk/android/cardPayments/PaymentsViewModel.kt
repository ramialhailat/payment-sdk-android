package payment.sdk.android.cardPayments

import android.app.Application
import androidx.annotation.Keep
import androidx.annotation.RestrictTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.WalletConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import payment.sdk.android.cardpayment.threedsecuretwo.ThreeDSecureFactory
import payment.sdk.android.cardpayment.threedsecuretwo.webview.toIntent
import payment.sdk.android.cardpayment.visaInstalments.model.NewCardDto
import payment.sdk.android.cardpayment.widget.DateFormatter
import payment.sdk.android.cardpayment.widget.LoadingMessage
import payment.sdk.android.core.CardMapping
import payment.sdk.android.core.OrderAmount
import payment.sdk.android.core.Utils.getQueryParameter
import payment.sdk.android.core.api.CoroutinesGatewayHttpClient
import payment.sdk.android.core.api.SDKHttpResponse
import payment.sdk.android.core.interactor.AuthApiInteractor
import payment.sdk.android.core.interactor.AuthResponse
import payment.sdk.android.core.interactor.CardPaymentInteractor
import payment.sdk.android.core.interactor.CardPaymentResponse
import payment.sdk.android.core.interactor.GetPayerIpInteractor
import payment.sdk.android.core.interactor.GooglePayAcceptInteractor
import payment.sdk.android.core.interactor.GooglePayConfigInteractor
import payment.sdk.android.core.interactor.VisaInstallmentPlanInteractor
import payment.sdk.android.core.interactor.VisaPlansResponse
import payment.sdk.android.googlepay.GooglePayConfigFactory
import payment.sdk.android.googlepay.GooglePayJsonConfig

@Keep
internal class PaymentsViewModel(
    private val cardPaymentsIntent: CardPaymentsLauncher.CardPaymentsIntent,
    private val authApiInteractor: AuthApiInteractor,
    private val cardPaymentInteractor: CardPaymentInteractor,
    private val visaInstalmentPlanInteractor: VisaInstallmentPlanInteractor,
    private val getPayerIpInteractor: GetPayerIpInteractor,
    private val googlePayConfigFactory: GooglePayConfigFactory,
    private val threeDSecureFactory: ThreeDSecureFactory,
    private val googlePayAcceptInteractor: GooglePayAcceptInteractor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private var _uiState: MutableStateFlow<PaymentsVMUiState> =
        MutableStateFlow(PaymentsVMUiState.Init)

    val uiState: StateFlow<PaymentsVMUiState> = _uiState.asStateFlow()

    private var _effects = MutableSharedFlow<PaymentsVMEffects>()

    val effect = _effects.asSharedFlow()

    fun authorize() {
        _uiState.update { PaymentsVMUiState.Loading(LoadingMessage.AUTH) }
        viewModelScope.launch(dispatcher) {
            val authCode = cardPaymentsIntent.payPageUrl.getQueryParameter("code")
            if (authCode.isNullOrBlank()) {
                return@launch
            }
            val authResponse = authApiInteractor.authenticate(
                authUrl = cardPaymentsIntent.authUrl, authCode = authCode
            )
            when (authResponse) {
                is AuthResponse.Error -> _effects.emit(PaymentsVMEffects.Failed(authResponse.error.message.orEmpty()))

                is AuthResponse.Success -> {
                    val googlePayConfig = googlePayConfigFactory.checkGooglePayConfig(
                        googlePayConfigUrl = cardPaymentsIntent.googlePayConfigUrl,
                        accessToken = authResponse.getAccessToken()
                    )
                    _uiState.update {
                        PaymentsVMUiState.Authorized(
                            accessToken = authResponse.getAccessToken(),
                            paymentCookie = authResponse.getPaymentCookie(),
                            orderUrl = authResponse.orderUrl,
                            supportedCards = CardMapping.mapSupportedCards(cardPaymentsIntent.allowedCards),
                            googlePayConfig = googlePayConfig,
                            showWallets = googlePayConfig?.canUseGooglePay ?: false,
                            orderAmount = OrderAmount(
                                cardPaymentsIntent.amount,
                                cardPaymentsIntent.currencyCode
                            )
                        )
                    }
                }
            }
        }
    }

    fun makeCardPayment(
        accessToken: String,
        paymentCookie: String,
        cardNumber: String,
        orderUrl: String,
        expiry: String,
        cvv: String,
        cardholderName: String
    ) {
        _uiState.update { PaymentsVMUiState.Loading(LoadingMessage.PAYMENT) }
        viewModelScope.launch(dispatcher) {
            val response = visaInstalmentPlanInteractor.getPlans(
                cardNumber = cardholderName,
                token = accessToken,
                selfUrl = cardPaymentsIntent.selfUrl
            )

            if (response is VisaPlansResponse.Success) {
                _effects.emit(
                    PaymentsVMEffects.ShowVisaPlans(
                        visaPlans = response.visaPlans,
                        paymentCookie = paymentCookie,
                        orderUrl = orderUrl,
                        accessToken = accessToken,
                        cvv = cvv,
                        newCardDto = NewCardDto(
                            cardNumber = cardNumber,
                            expiry = DateFormatter.formatExpireDateForApi(expiry),
                            customerName = cardholderName,
                            cvv = cvv
                        )
                    )
                )
            } else {
                val payerIp = getPayerIpInteractor.getPayerIp(cardPaymentsIntent.payPageUrl)
                initiateCardPayment(
                    paymentCookie = paymentCookie,
                    orderUrl = orderUrl,
                    cardNumber = cardNumber,
                    expiry = expiry,
                    cvv = cvv,
                    cardholderName = cardholderName,
                    payerIp = payerIp
                )
            }
        }
    }

    fun acceptGooglePay(paymentDataJson: String) {
        viewModelScope.launch(dispatcher) {
            val currentState = uiState.value
            val googlePayUrl = cardPaymentsIntent.googlePayUrl

            if (currentState !is PaymentsVMUiState.Authorized || googlePayUrl == null) {
                _effects.emit(PaymentsVMEffects.Failed("Authorization or Google Pay URL is missing"))
                return@launch
            }

            val accessToken = currentState.accessToken
            val response =
                googlePayAcceptInteractor.accept(googlePayUrl, accessToken, paymentDataJson)

            when (response) {
                is SDKHttpResponse.Failed -> _effects.emit(PaymentsVMEffects.Failed("Google Pay accept failed: ${response.error.message}"))
                is SDKHttpResponse.Success -> _effects.emit(PaymentsVMEffects.Captured)
            }
        }
    }

    private suspend fun initiateCardPayment(
        paymentCookie: String,
        orderUrl: String,
        cardNumber: String,
        expiry: String,
        cvv: String,
        cardholderName: String,
        payerIp: String?
    ) {
        val response = cardPaymentInteractor.makeCardPayment(
            paymentCookie = paymentCookie,
            paymentUrl = cardPaymentsIntent.cardPaymentUrl,
            pan = cardNumber,
            expiry = DateFormatter.formatExpireDateForApi(expiry),
            cvv = cvv,
            cardHolder = cardholderName,
            payerIp = payerIp
        )

        if (response is CardPaymentResponse.Success) {
            when (response.paymentResponse.state) {
                "AUTHORISED" -> _effects.emit(PaymentsVMEffects.PaymentAuthorised)
                "PURCHASED" -> _effects.emit(PaymentsVMEffects.Purchased)
                "CAPTURED" -> _effects.emit(PaymentsVMEffects.Captured)
                "POST_AUTH_REVIEW" -> _effects.emit(PaymentsVMEffects.PostAuthReview)
                "AWAIT_3DS" -> {
                    try {
                        if (response.paymentResponse.isThreeDSecureTwo()) {
                            val request = threeDSecureFactory.buildThreeDSecureTwoDto(
                                paymentResponse = response.paymentResponse,
                                orderUrl = orderUrl,
                                paymentCookie = paymentCookie
                            )
                            _effects.emit(PaymentsVMEffects.InitiateThreeDSTwo(request))
                        } else {
                            val request =
                                threeDSecureFactory.buildThreeDSecureDto(paymentResponse = response.paymentResponse)
                            _effects.emit(PaymentsVMEffects.InitiateThreeDS(request))
                        }

                    } catch (e: IllegalArgumentException) {
                        _effects.emit(PaymentsVMEffects.Failed(e.message.orEmpty()))
                    }
                }

                "AWAITING_PARTIAL_AUTH_APPROVAL" -> {
                    response.paymentResponse.toIntent(paymentCookie).let { intent ->
                        _effects.emit(PaymentsVMEffects.InitiatePartialAuth(intent))
                    }
                }

                "FAILED" -> _effects.emit(PaymentsVMEffects.Failed(" Payment Failed ${response.paymentResponse.threeDSOne?.summaryText.orEmpty()}"))
                else -> _effects.emit(PaymentsVMEffects.Failed("Unknown payment state: $uiState"))
            }
        } else {
            _effects.emit(PaymentsVMEffects.Failed((response as CardPaymentResponse.Error).error.message.orEmpty()))
        }
    }

    internal class Factory(private val cardPaymentsIntent: CardPaymentsLauncher.CardPaymentsIntent) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>, extras: CreationExtras
        ): T {
            val walletOptions =
                Wallet.WalletOptions.Builder().setEnvironment(WalletConstants.ENVIRONMENT_TEST)
                    .build()
            val httpClient = CoroutinesGatewayHttpClient()
            return PaymentsViewModel(
                cardPaymentsIntent = cardPaymentsIntent,
                authApiInteractor = AuthApiInteractor(httpClient),
                cardPaymentInteractor = CardPaymentInteractor(httpClient),
                visaInstalmentPlanInteractor = VisaInstallmentPlanInteractor(httpClient),
                getPayerIpInteractor = GetPayerIpInteractor(httpClient),
                threeDSecureFactory = ThreeDSecureFactory(),
                googlePayConfigFactory = GooglePayConfigFactory(
                    paymentsClient = Wallet.getPaymentsClient(
                        extras.requireApplication(), walletOptions
                    ),
                    allowedWallets = cardPaymentsIntent.allowedWallets.toMutableList().apply {
                        add("GOOGLE_PAY")
                    },
                    googlePayJsonConfig = GooglePayJsonConfig(
                        cardPaymentsIntent.amount,
                        cardPaymentsIntent.currencyCode
                    ),
                    googlePayConfigInteractor = GooglePayConfigInteractor(httpClient)
                ),
                googlePayAcceptInteractor = GooglePayAcceptInteractor(httpClient)
            ) as T
        }
    }
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun CreationExtras.requireApplication(): Application {
    return requireNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
}