package com.symphony.bdk.core.service.datafeed.impl;

import com.symphony.bdk.core.auth.AuthSession;
import com.symphony.bdk.core.auth.exception.AuthUnauthorizedException;
import com.symphony.bdk.core.config.model.BdkConfig;
import com.symphony.bdk.core.config.model.BdkLoadBalancingConfig;
import com.symphony.bdk.core.retry.RetryWithRecoveryBuilder;
import com.symphony.bdk.core.service.datafeed.DatafeedLoop;
import com.symphony.bdk.core.service.datafeed.RealTimeEventListener;
import com.symphony.bdk.gen.api.DatafeedApi;
import com.symphony.bdk.gen.api.model.V4Event;
import com.symphony.bdk.http.api.ApiClient;
import com.symphony.bdk.http.api.ApiException;

import lombok.extern.slf4j.Slf4j;
import org.apiguardian.api.API;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for implementing the datafeed services. A datafeed services can help a bot subscribe or unsubscribe
 * a {@link RealTimeEventListener} and handle the received event by the subscribed listeners.
 */
@Slf4j
@API(status = API.Status.INTERNAL)
abstract class AbstractDatafeedLoop implements DatafeedLoop {

  protected final AuthSession authSession;
  protected final BdkConfig bdkConfig;
  protected final RetryWithRecoveryBuilder retryWithRecoveryBuilder;
  protected DatafeedApi datafeedApi;
  protected ApiClient apiClient;

  // access needs to be thread safe (DF loop is usually running on its own thread)
  private final List<RealTimeEventListener> listeners;

  public AbstractDatafeedLoop(DatafeedApi datafeedApi, AuthSession authSession, BdkConfig config) {
    this.datafeedApi = datafeedApi;
    this.listeners = new ArrayList<>();
    this.authSession = authSession;
    this.bdkConfig = config;
    this.apiClient = datafeedApi.getApiClient();
    this.retryWithRecoveryBuilder = new RetryWithRecoveryBuilder<>()
        .retryConfig(config.getDatafeedRetryConfig())
        .recoveryStrategy(Exception.class, () -> this.apiClient.rotate())  //always rotate in case of any error
        .recoveryStrategy(ApiException::isUnauthorized, this::refresh);

    final BdkLoadBalancingConfig loadBalancing = config.getAgent().getLoadBalancing();
    if (loadBalancing != null && !loadBalancing.isStickiness()) {
      log.warn("DF used with agent load balancing configured with stickiness false. DF calls will still be sticky.");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void subscribe(RealTimeEventListener listener) {
    synchronized (listeners) {
      listeners.add(listener);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void unsubscribe(RealTimeEventListener listener) {
    synchronized (listeners) {
      listeners.remove(listener);
    }
  }

  /**
   * Handle a received listener by using the subscribed {@link RealTimeEventListener}.
   *
   * @param events List of Datafeed events to be handled
   */
  protected void handleV4EventList(List<V4Event> events) {
    for (V4Event event : events) {
      if (event == null || event.getType() == null) {
        continue;
      }

      try {
        RealTimeEventType eventType = RealTimeEventType.valueOf(event.getType());
        synchronized (listeners) {
          for (RealTimeEventListener listener : listeners) {
            if (listener.isAcceptingEvent(event, bdkConfig.getBot().getUsername())) {
              eventType.dispatch(listener, event);
            }
          }
        }
      } catch (IllegalArgumentException e) {
        log.warn("Receive events with unknown type: {}", event.getType());
      }
    }
  }

  protected void refresh() throws AuthUnauthorizedException {
    log.info("Re-authenticate and try again");
    authSession.refresh();
  }

  protected void setDatafeedApi(DatafeedApi datafeedApi) {
    this.datafeedApi = datafeedApi;
  }
}
