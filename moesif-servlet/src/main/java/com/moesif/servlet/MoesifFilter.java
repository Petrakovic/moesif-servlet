package com.moesif.servlet;

import java.io.IOException;
import java.util.logging.Logger;
import java.util.Date;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.moesif.api.models.EventModel;
import com.moesif.api.models.EventRequestModel;
import com.moesif.api.models.EventResponseModel;
import com.moesif.api.models.EventBuilder;
import com.moesif.api.models.EventRequestBuilder;
import com.moesif.api.models.EventResponseBuilder;

import com.moesif.api.APIHelper;
import com.moesif.api.MoesifAPIClient;
import com.moesif.api.http.client.APICallBack;
import com.moesif.api.http.client.HttpContext;
import com.moesif.api.controllers.APIController;

import com.moesif.servlet.utils.IpAddress;
import com.moesif.servlet.wrappers.LoggingHttpServletRequestWrapper;
import com.moesif.servlet.wrappers.LoggingHttpServletResponseWrapper;
import org.apache.commons.lang3.StringUtils;

public class MoesifFilter implements Filter {

  private static final Logger logger = Logger.getLogger(MoesifFilter.class.toString());

  private String applicationId;
  private MoesifConfiguration config;
  private MoesifAPIClient moesifApi;
  private boolean debug;

  /**
   * Default Constructor, please set ApplicationId before use.
   */
  public MoesifFilter() {
    this.config = new MoesifConfigurationAdapter();
    this.debug = false;
  }

  /**
   * Constructor
   * @param    applicationId   Required parameter: obtained from your moesif Account.
   */
  public MoesifFilter(String applicationId) {
    this.applicationId = applicationId;
    this.config = new MoesifConfigurationAdapter();
    this.moesifApi = new MoesifAPIClient(applicationId);
    this.debug = false;
  }

  /**
   * Constructor
   * @param    applicationId   Required parameter: obtained from your moesif Account.
   * @param    debug Flag for turning debug messages on.
   */
  public MoesifFilter(String applicationId, boolean debug) {
    this.applicationId = applicationId;
    this.config = new MoesifConfigurationAdapter();
    this.moesifApi = new MoesifAPIClient(applicationId);
    this.debug = debug;
  }

  /**
   * Constructor
   * @param    applicationId   Required parameter: obtained from your moesif Account.
   * @param    config MoesifConfiguration Object.
   */
  public MoesifFilter(String applicationId, MoesifConfiguration config) {
    this.applicationId = applicationId;
    this.config = config;
    this.moesifApi = new MoesifAPIClient(applicationId);
    this.debug = false;
  }

  /**
   * Constructor
   * @param    applicationId   Required parameter: obtained from your moesif Account.
   * @param    config MoesifConfiguration Object
   * @param    debug boolean
   */
  public MoesifFilter(String applicationId, MoesifConfiguration config, boolean debug) {
    this.applicationId = applicationId;
    this.config = config;
    this.moesifApi = new MoesifAPIClient(applicationId);
    this.debug = debug;
  }

  /**
   * Sets the Moesif Application Id.
   * @param    applicationId   Required parameter: obtained from your moesif Account.
   */
  public void setApplicationId(String applicationId) {
    this.applicationId = applicationId;
    this.moesifApi = new MoesifAPIClient(applicationId);
  }

  /**
   * Sets the MoesifConfiguration
   * @param    config MoesifConfiguration Object
   */
  public void setConfigure(MoesifConfiguration config) {
    this.config = config;
  }

  /**
   * Sets the debug flag
   * @param    debug boolean
   */
  public void setDebug(boolean debug) {
    this.debug = debug;
  }

  /**
   * Get the underlying APIController
   * @return	Returns the APIController instance
   */
  public APIController getAPI() {
    if (moesifApi != null) {
      return moesifApi.getAPI();
    }
    return null;
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    if (debug) {
      logger.fine("init filter");
    }
    String appId = filterConfig.getInitParameter("application-id");
    if (appId != null) {
      this.applicationId = appId;
      this.moesifApi = new MoesifAPIClient(this.applicationId);
    }
    String debug = filterConfig.getInitParameter("debug");
    if (debug != null) {
      if (debug.equals("true")) {
        this.debug = true;
      }
    }
  }

  @Override
  public void destroy() {
    if (debug) {
      logger.fine("destroying filter");
    }
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
    if (debug) {
      logger.fine("filtering request");
    }

    Date startDate = new Date();

    if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
      logger.warning("MoesifFilter was called for non HTTP requests");
      filterChain.doFilter(request, response);
      return;
    }

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    if (config.skip(httpRequest, httpResponse)) {
      filterChain.doFilter(httpRequest, httpResponse);
      return;
    }

    LoggingHttpServletRequestWrapper requestWrapper = new LoggingHttpServletRequestWrapper(httpRequest);
    LoggingHttpServletResponseWrapper responseWrapper = new LoggingHttpServletResponseWrapper(httpResponse);

    EventRequestModel eventRequestModel = getEventRequestModel(requestWrapper,
        startDate, config.getApiVersion(httpRequest, httpResponse));

    // pass to next step in the chain.
    filterChain.doFilter(requestWrapper, responseWrapper);

    Date endDate = new Date();
    EventResponseModel eventResponseModel = getEventResponseModel(responseWrapper, endDate);

    httpResponse.getOutputStream().write(responseWrapper.getContentAsBytes());

    sendEvent(
        eventRequestModel,
        eventResponseModel,
        config.identifyUser(httpRequest, httpResponse),
        config.getSessionToken(httpRequest, httpResponse),
        config.getTags(httpRequest, httpResponse)
    );
  }


  private EventRequestModel getEventRequestModel(LoggingHttpServletRequestWrapper requestWrapper, Date date, String apiVersion) {
    EventRequestBuilder eventRequestBuilder = new EventRequestBuilder();
    eventRequestBuilder
        .time(date)
        .uri(getFullURL(requestWrapper))
        .headers(requestWrapper.getHeaders())
        .verb(requestWrapper.getMethod())
        .ipAddress(IpAddress.getClientIp(requestWrapper));

    if (StringUtils.isNotEmpty(apiVersion)) {
      eventRequestBuilder.apiVersion(apiVersion);
    }

    String content = requestWrapper.getContent();

    if (content != null) {
      BodyParser.BodyWrapper bodyWrapper = BodyParser.parseBody(requestWrapper.getHeaders(), content);
      eventRequestBuilder.body(bodyWrapper.body);
      eventRequestBuilder.transferEncoding(bodyWrapper.transferEncoding);
    }

    return eventRequestBuilder.build();
  }

  private EventResponseModel getEventResponseModel(LoggingHttpServletResponseWrapper responseWrapper, Date date) {
    EventResponseBuilder eventResponseBuilder = new EventResponseBuilder();
    eventResponseBuilder
        .time(date)
        .status(responseWrapper.getStatus())
        .headers(responseWrapper.getHeaders());

    String content = responseWrapper.getContent();

    if (content != null) {
      BodyParser.BodyWrapper bodyWrapper = BodyParser.parseBody(responseWrapper.getHeaders(), content);
      eventResponseBuilder.body(bodyWrapper.body);
      eventResponseBuilder.transferEncoding(bodyWrapper.transferEncoding);
    }

    return eventResponseBuilder.build();
  }

  private void sendEvent(EventRequestModel eventRequestModel,
                           EventResponseModel eventResponseModel,
                           String userId,
                           String sessionToken,
                           String tags) {
    EventBuilder eb = new EventBuilder();
    eb.request(eventRequestModel);
    eb.response(eventResponseModel);
    if (userId != null) {
      eb.userId(userId);
    }
    if (sessionToken != null) {
      eb.sessionToken(sessionToken);
    }
    if (tags != null) {
      eb.tags(tags);
    }

    EventModel event = eb.build();

    if (this.moesifApi != null) {
      // actually send the event here.

      APICallBack<Object> callBack = new APICallBack<Object>() {
        public void onSuccess(HttpContext context, Object response) {
          if (debug) {
            logger.info("send to Moesif success");
          }
        }

        public void onFailure(HttpContext context, Throwable error) {
          if (debug) {
            logger.info("send to Moesif error ");
            logger.info( error.toString());
          }
        }
      };

      try {

        EventModel maskedEvent = config.maskContent(event);
        if (maskedEvent == null) {
          logger.severe("maskContent() returned a null object, not allowed");
        }

        moesifApi.getAPI().createEventAsync(maskedEvent, callBack);

      } catch(Exception e) {
        if (debug) {
          logger.warning("send to Moesif failed " + e.toString());
        }
      }

    } else {
      logger.warning("The application Id should be set before using MoesifFilter");
    }
  }

  static String getFullURL(HttpServletRequest request) {
    StringBuffer requestURL = request.getRequestURL();
    String queryString = request.getQueryString();

    if (queryString == null) {
      return requestURL.toString();
    } else {
      return requestURL.append('?').append(queryString).toString();
    }
  }
}