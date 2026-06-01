import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, beforeEach } from "vitest";
import AlertsBanner from "./AlertsBanner";
import { useAlertStore } from "@/stores/alertStore";

beforeEach(() => {
  useAlertStore.getState().clear();
});

describe("AlertsBanner", () => {
  it("renders nothing when there are no active alerts", () => {
    const { container } = render(<AlertsBanner />);
    expect(container.firstChild).toBeNull();
  });

  it("renders a card per active alert", () => {
    useAlertStore.getState().push({
      symbol: "AAPL",
      alertType: "FLOW_TOXICITY",
      severity: "HIGH",
      message: "Mean markout 7.2 bps > 5",
      timestamp: new Date().toISOString(),
    });
    useAlertStore.getState().push({
      symbol: "MSFT",
      alertType: "RECON_BREAK",
      severity: "CRITICAL",
      message: "Missing fill on order abc",
      timestamp: new Date().toISOString(),
    });
    render(<AlertsBanner />);
    expect(screen.getAllByTestId("alert-card")).toHaveLength(2);
    expect(screen.getByText(/Mean markout/)).toBeInTheDocument();
    expect(screen.getByText(/Missing fill/)).toBeInTheDocument();
  });

  it("dismisses an alert when the X is clicked", async () => {
    useAlertStore.getState().push({
      symbol: "AAPL",
      alertType: "FLOW_TOXICITY",
      severity: "HIGH",
      message: "Bad markout",
      timestamp: new Date().toISOString(),
    });
    const user = userEvent.setup();
    const { container } = render(<AlertsBanner />);
    await user.click(screen.getByTestId("alert-dismiss"));
    await waitFor(() => {
      expect(container.firstChild).toBeNull();
    });
  });

  it("caps the visible stack at 5 cards", () => {
    for (let i = 0; i < 7; i++) {
      useAlertStore.getState().push({
        symbol: "AAPL",
        alertType: "FLOW_TOXICITY",
        severity: "HIGH",
        message: `alert ${String(i)}`,
        timestamp: new Date().toISOString(),
      });
    }
    render(<AlertsBanner />);
    expect(screen.getAllByTestId("alert-card")).toHaveLength(5);
  });
});
