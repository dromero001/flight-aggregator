package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.Admission;
import com.flightaggregator.application.port.in.AdmitRequest;
import com.flightaggregator.application.port.in.RateLimitedAction;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double: answers the admission the test sets, admitted by default, and records who asked for
 * what. Spring keeps one per cached context, so each test starts with {@link #reset}.
 */
final class RecordingAdmission implements AdmitRequest {

  /** One request counted: the client's address and the action it asked for. */
  record Asked(String clientAddress, RateLimitedAction action) {}

  private final List<Asked> asked = new ArrayList<>();
  private Admission admission = new Admission.Admitted();

  void reset() {
    asked.clear();
    admission = new Admission.Admitted();
  }

  void answer(Admission admission) {
    this.admission = admission;
  }

  List<Asked> asked() {
    return asked;
  }

  @Override
  public Admission admit(String clientAddress, RateLimitedAction action) {
    asked.add(new Asked(clientAddress, action));
    return admission;
  }
}
