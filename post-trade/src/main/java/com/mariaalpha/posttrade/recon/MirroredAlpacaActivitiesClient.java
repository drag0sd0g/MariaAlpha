package com.mariaalpha.posttrade.recon;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class MirroredAlpacaActivitiesClient implements AlpacaActivitiesClient {

  @Override
  public List<ExternalFill> activitiesForDate(LocalDate date, List<InternalFill> internalFills) {
    List<ExternalFill> out = new ArrayList<>(internalFills.size());
    for (InternalFill f : internalFills) {
      out.add(
          new ExternalFill(
              f.fillId() == null ? null : f.fillId().toString(),
              f.exchangeOrderId(),
              f.clientOrderId(),
              f.symbol(),
              f.side(),
              f.price(),
              f.quantity(),
              f.filledAt()));
    }
    return out;
  }
}
