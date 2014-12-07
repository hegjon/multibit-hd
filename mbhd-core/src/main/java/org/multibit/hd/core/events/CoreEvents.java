package org.multibit.hd.core.events;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import org.joda.time.DateTime;
import org.multibit.hd.core.concurrent.SafeExecutors;
import org.multibit.hd.core.dto.*;
import org.multibit.hd.core.services.CoreServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * <p>Factory to provide the following to application API:</p>
 * <ul>
 * <li>Entry point to broadcast core events</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class CoreEvents {

  private static final Logger log = LoggerFactory.getLogger(CoreEvents.class);

  private static boolean waitingToFireSlowTransactionSeenEvent = false;
  private static final Object lockObject = new Object();

  // Provide a CoreEvent thread pool to ensure non-UI events are isolated from the EDT
  private static ListeningExecutorService eventExecutor = SafeExecutors.newFixedThreadPool(10, "core-events");

  // Provide a slower transaction seen thread that is isolated from the EDT
  private static ListeningScheduledExecutorService txSeenExecutor = SafeExecutors.newSingleThreadScheduledExecutor("tx-seen");

  /**
   * Utilities have a private constructor
   */
  private CoreEvents() {
  }

  /**
   * <p>Broadcast a new "exchange rate changed" event</p>
   *
   * @param rate         The rate in the local currency against Bitcoin (e.g. "1000" means 1000 local = 1 bitcoin)
   * @param currency     The local currency
   * @param rateProvider The rate provider (e.g. "Bitstamp" or absent if unknown)
   * @param expires      The expiry timestamp of this rate
   */
  public static void fireExchangeRateChangedEvent(
    final BigDecimal rate,
    final Currency currency,
    final Optional<String> rateProvider,
    final DateTime expires
  ) {

    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          ExchangeRateChangedEvent event = new ExchangeRateChangedEvent(rate, currency, rateProvider, expires);
          CoreServices.uiEventBus.post(event);
          log.debug("Firing 'exchange rate changed' event: {}", event);
        }
      });

  }

  /**
   * <p>Broadcast a new "exchange status changed" event</p>
   *
   * @param exchangeSummary The exchange summary
   */
  public static void fireExchangeStatusChangedEvent(final ExchangeSummary exchangeSummary) {

    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'exchange status changed' event");
          CoreServices.uiEventBus.post(new ExchangeStatusChangedEvent(exchangeSummary));
        }
      });

  }

  /**
   * <p>Broadcast TransactionCreationEvent</p>
   *
   * @param transactionCreationEvent containing transaction creation information
   */
  public static void fireTransactionCreationEvent(final TransactionCreationEvent transactionCreationEvent) {

    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'transactionCreation' event");
          CoreServices.uiEventBus.post(transactionCreationEvent);
        }
      });

  }

  /**
   * <p>Broadcast BitcoinSentEvent</p>
   *
   * @param bitcoinSentEvent containing send information
   */
  public static void fireBitcoinSentEvent(final BitcoinSentEvent bitcoinSentEvent) {

    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'bitcoin sent' event");
          CoreServices.uiEventBus.post(bitcoinSentEvent);
        }
      });
  }

  /**
   * Broadcast ChangePasswordResultEvent
   */
  public static void fireChangePasswordResultEvent(final ChangePasswordResultEvent changePasswordResultEvent) {

    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'change password result' event");
          CoreServices.uiEventBus.post(changePasswordResultEvent);
        }
      });
  }

  /**
   * <p>Broadcast TransactionSeenEvent</p>
   *
   * @param transactionSeenEvent containing transaction information
   */
  public static void fireTransactionSeenEvent(final TransactionSeenEvent transactionSeenEvent) {

    // Use the tx-seen pool
    txSeenExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          CoreServices.uiEventBus.post(transactionSeenEvent);
          consolidateTransactionSeenEvents();
        }
      });
  }

  /**
   * Consolidate many transactionSeenEvents into a single call per (slow) time interval
   */
  private static void consolidateTransactionSeenEvents() {

    synchronized (lockObject) {
      if (!waitingToFireSlowTransactionSeenEvent) {
        // Fire in the future
        waitingToFireSlowTransactionSeenEvent = true;
        txSeenExecutor.schedule(
          new Callable() {
            @Override
            public Object call() throws Exception {
              CoreServices.uiEventBus.post(new SlowTransactionSeenEvent());
              synchronized (lockObject) {
                waitingToFireSlowTransactionSeenEvent = false;
              }
              return null;
            }
          }, 1, TimeUnit.SECONDS);
      }
    }

  }

  /**
   * <p>Broadcast a new "Bitcoin network changed" event</p>
   *
   * @param bitcoinNetworkSummary The Bitcoin network summary
   */
  public static void fireBitcoinNetworkChangedEvent(final BitcoinNetworkSummary bitcoinNetworkSummary) {

    if (log.isTraceEnabled()) {
      if (bitcoinNetworkSummary.getPercent() > 0) {
        log.trace("Firing 'Bitcoin network changed' event: {}%", bitcoinNetworkSummary.getPercent());
      } else {
        log.trace("Firing 'Bitcoin network changed' event");
      }
    }

    CoreServices.uiEventBus.post(new BitcoinNetworkChangedEvent(bitcoinNetworkSummary));

  }

  /**
   * <p>Broadcast a new "Backup wallet has been loaded" event</p>
   *
   * @param walletId         the walletId of the wallet that had the backup loaded
   * @param backupWalletFile The backup wallet that was loaded
   */
  public static void fireBackupWalletLoadedEvent(final WalletId walletId, final File backupWalletFile) {
    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'backup wallet loaded' event");
          CoreServices.uiEventBus.post(new BackupWalletLoadedEvent(walletId, backupWalletFile));
        }
      });
  }

  /**
   * <p>Broadcast a new "security" event</p>
   *
   * @param securitySummary The security summary
   */
  public static void fireSecurityEvent(final SecuritySummary securitySummary) {
    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'security' event");
          CoreServices.uiEventBus.post(new SecurityEvent(securitySummary));
        }
      });
  }

  /**
   * <p>Broadcast a new "history changed" event</p>
   *
   * @param historyEntry The history entry from the History service
   */
  public static void fireHistoryChangedEvent(final HistoryEntry historyEntry) {
    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'history changed' event");
          CoreServices.uiEventBus.post(new HistoryChangedEvent(historyEntry));
        }
      });
  }

  /**
   * <p>Broadcast a new "shutdown" event</p>
   *
   * <p>Typically this is for SOFT shutdowns. A HARD shutdown should call <code>CoreServices.shutdownNow()</code> directly.</p>
   *
   * @param shutdownType The shutdown type
   */
  public static void fireShutdownEvent(final ShutdownEvent.ShutdownType shutdownType) {
    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.info("Firing 'shutdown' event: {}", shutdownType);
          CoreServices.uiEventBus.post(new ShutdownEvent(shutdownType));
        }
      });
  }

  /**
   * <p>Broadcast a new "configuration changed" event</p>
   */
  public static void fireConfigurationChangedEvent() {
    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'configuration changed' event");
          CoreServices.uiEventBus.post(new ConfigurationChangedEvent());
        }
      });
  }

  /**
   * <p>Broadcast a new "export performed" event</p>
   *
   * @param exportPerformedEvent The export performed event
   */
  public static void fireExportPerformedEvent(final ExportPerformedEvent exportPerformedEvent) {
    eventExecutor.submit(
      new Runnable() {
        @Override
        public void run() {
          log.trace("Firing 'export performed' event");
          CoreServices.uiEventBus.post(exportPerformedEvent);
        }
      });
  }

}
