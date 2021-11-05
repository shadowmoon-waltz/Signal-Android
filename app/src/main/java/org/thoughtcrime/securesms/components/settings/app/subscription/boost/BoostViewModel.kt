package org.thoughtcrime.securesms.components.settings.app.subscription.boost

import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.wallet.PaymentData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.PublishSubject
import org.signal.core.util.logging.Log
import org.signal.core.util.money.FiatMoney
import org.signal.donations.GooglePayApi
import org.thoughtcrime.securesms.badges.models.Badge
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationEvent
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationPaymentRepository
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.PlatformCurrencyUtil
import org.thoughtcrime.securesms.util.livedata.Store
import java.math.BigDecimal
import java.util.Currency

class BoostViewModel(
  private val boostRepository: BoostRepository,
  private val donationPaymentRepository: DonationPaymentRepository,
  private val fetchTokenRequestCode: Int
) : ViewModel() {

  private val store = Store(BoostState(currencySelection = SignalStore.donationsValues().getBoostCurrency()))
  private val eventPublisher: PublishSubject<DonationEvent> = PublishSubject.create()
  private val disposables = CompositeDisposable()

  val state: LiveData<BoostState> = store.stateLiveData
  val events: Observable<DonationEvent> = eventPublisher.observeOn(AndroidSchedulers.mainThread())

  private var boostToPurchase: Boost? = null

  override fun onCleared() {
    disposables.clear()
  }

  fun getSupportedCurrencyCodes(): List<String> {
    return store.state.supportedCurrencyCodes
  }

  fun refresh() {
    disposables.clear()

    val currencyObservable = SignalStore.donationsValues().observableBoostCurrency
    val allBoosts = boostRepository.getBoosts()
    val boostBadge = boostRepository.getBoostBadge()

    disposables += Observable.combineLatest(currencyObservable, allBoosts.toObservable(), boostBadge.toObservable()) { currency, boostMap, badge ->
      val boostList = if (currency in boostMap) {
        boostMap[currency]!!
      } else {
        SignalStore.donationsValues().setBoostCurrency(PlatformCurrencyUtil.USD)
        listOf()
      }

      BoostInfo(boostList, boostList[2], badge, boostMap.keys)
    }.subscribeBy(
      onNext = { info ->
        store.update {
          it.copy(
            boosts = info.boosts,
            selectedBoost = if (it.selectedBoost in info.boosts) it.selectedBoost else info.defaultBoost,
            boostBadge = it.boostBadge ?: info.boostBadge,
            stage = if (it.stage == BoostState.Stage.INIT || it.stage == BoostState.Stage.FAILURE) BoostState.Stage.READY else it.stage,
            supportedCurrencyCodes = info.supportedCurrencies.map(Currency::getCurrencyCode)
          )
        }
      },
      onError = { throwable ->
        Log.w(TAG, "Could not load boost information", throwable)
        store.update {
          it.copy(stage = BoostState.Stage.FAILURE)
        }
      }
    )

    disposables += donationPaymentRepository.isGooglePayAvailable().subscribeBy(
      onComplete = { store.update { it.copy(isGooglePayAvailable = true) } },
      onError = { eventPublisher.onNext(DonationEvent.GooglePayUnavailableError(it)) }
    )

    disposables += currencyObservable.subscribeBy { currency ->
      store.update {
        it.copy(
          currencySelection = currency,
          isCustomAmountFocused = false,
          customAmount = FiatMoney(
            BigDecimal.ZERO, currency
          )
        )
      }
    }
  }

  fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
  ) {
    val boost = boostToPurchase
    boostToPurchase = null

    donationPaymentRepository.onActivityResult(
      requestCode, resultCode, data, this.fetchTokenRequestCode,
      object : GooglePayApi.PaymentRequestCallback {
        override fun onSuccess(paymentData: PaymentData) {
          if (boost != null) {
            eventPublisher.onNext(DonationEvent.RequestTokenSuccess)

            donationPaymentRepository.continuePayment(boost.price, paymentData).subscribeBy(
              onError = { throwable ->
                store.update { it.copy(stage = BoostState.Stage.READY) }
                eventPublisher.onNext(DonationEvent.PaymentConfirmationError(throwable))
              },
              onComplete = {
                store.update { it.copy(stage = BoostState.Stage.READY) }
                eventPublisher.onNext(DonationEvent.PaymentConfirmationSuccess(store.state.boostBadge!!))
              }
            )
          } else {
            store.update { it.copy(stage = BoostState.Stage.READY) }
          }
        }

        override fun onError() {
          store.update { it.copy(stage = BoostState.Stage.READY) }
          eventPublisher.onNext(DonationEvent.RequestTokenError)
        }

        override fun onCancelled() {
          store.update { it.copy(stage = BoostState.Stage.READY) }
        }
      }
    )
  }

  fun requestTokenFromGooglePay(label: String) {
    val snapshot = store.state
    if (snapshot.selectedBoost == null) {
      return
    }

    store.update { it.copy(stage = BoostState.Stage.PAYMENT_PIPELINE) }

    boostToPurchase = if (snapshot.isCustomAmountFocused) {
      Boost(snapshot.customAmount)
    } else {
      snapshot.selectedBoost
    }

    donationPaymentRepository.requestTokenFromGooglePay(snapshot.selectedBoost.price, label, fetchTokenRequestCode)
  }

  fun setSelectedBoost(boost: Boost) {
    store.update {
      it.copy(
        isCustomAmountFocused = false,
        selectedBoost = boost
      )
    }
  }

  fun setCustomAmount(amount: String) {
    val bigDecimalAmount = if (amount.isEmpty()) {
      BigDecimal.ZERO
    } else {
      BigDecimal(amount)
    }

    store.update { it.copy(customAmount = FiatMoney(bigDecimalAmount, it.customAmount.currency)) }
  }

  fun setCustomAmountFocused(isFocused: Boolean) {
    store.update { it.copy(isCustomAmountFocused = isFocused) }
  }

  private data class BoostInfo(val boosts: List<Boost>, val defaultBoost: Boost?, val boostBadge: Badge, val supportedCurrencies: Set<Currency>)

  class Factory(
    private val boostRepository: BoostRepository,
    private val donationPaymentRepository: DonationPaymentRepository,
    private val fetchTokenRequestCode: Int
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
      return modelClass.cast(BoostViewModel(boostRepository, donationPaymentRepository, fetchTokenRequestCode))!!
    }
  }

  companion object {
    private val TAG = Log.tag(BoostViewModel::class.java)
  }
}
