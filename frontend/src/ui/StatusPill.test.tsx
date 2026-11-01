import { render, screen } from "@testing-library/react";
import { type RunStatus, StatusPill } from "./StatusPill";

test.each([
  ["SUCCESS", "Succeeded"], ["RUNNING", "Running"], ["FAILED", "Failed"],
  ["AWAITING_APPROVAL", "Awaiting approval"], ["CANCELLED", "Cancelled"], ["QUEUED", "Queued"],
])("StatusPill %s shows a text label, not color only", (status, label) => {
  render(<StatusPill status={status as RunStatus} />);
  expect(screen.getByText(label)).toBeVisible();
  expect(screen.getByTestId("status-icon")).toHaveAttribute("aria-hidden", "true");
});
