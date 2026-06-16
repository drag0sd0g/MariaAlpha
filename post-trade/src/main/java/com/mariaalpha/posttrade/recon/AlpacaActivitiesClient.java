package com.mariaalpha.posttrade.recon;

import java.time.LocalDate;
import java.util.List;

public interface AlpacaActivitiesClient {

  List<ExternalFill> activitiesForDate(LocalDate date, List<InternalFill> internalFills);
}
