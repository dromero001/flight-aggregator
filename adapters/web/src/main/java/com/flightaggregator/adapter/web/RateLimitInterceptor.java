package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.Admission;
import com.flightaggregator.application.port.in.AdmitRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Limits the public search and re-price per client address (§6.6): a request that reached an
 * endpoint marked {@link RateLimited} is counted under that endpoint's action, and one past its
 * limit goes no further, answered by {@link ProblemResponses} as a 429 with {@code Retry-After}.
 * The action comes from the endpoint Spring MVC matched by method and path, so it follows the
 * mapping, a {@code HEAD} of the search included. The address is the request's remote address,
 * which Tomcat takes from {@code X-Forwarded-For} only when the connection comes from an internal
 * proxy, so a client cannot choose its own counter.
 */
public final class RateLimitInterceptor implements HandlerInterceptor {

  private final AdmitRequest admit;

  public RateLimitInterceptor(AdmitRequest admit) {
    this.admit = admit;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod endpoint
        && endpoint.getMethodAnnotation(RateLimited.class) instanceof RateLimited limited
        && admit.admit(request.getRemoteAddr(), limited.value())
            instanceof Admission.Rejected(var retryAfter)) {
      throw new RetryLaterException(
          HttpStatus.TOO_MANY_REQUESTS,
          "Too many requests from this address: try again after the seconds in Retry-After.",
          retryAfter);
    }
    return true;
  }
}
