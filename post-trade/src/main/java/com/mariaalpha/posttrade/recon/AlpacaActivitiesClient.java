package com.mariaalpha.posttrade.recon;

import java.time.LocalDate;
import java.util.List;

/** Sources the day's external fill activities. Two impls — HTTP (real Alpaca) and mirror (sim). */
public interface AlpacaActivitiesClient {

  List<ExternalFill> activitiesForDate(LocalDate date, List<InternalFill> internalFills);
}
