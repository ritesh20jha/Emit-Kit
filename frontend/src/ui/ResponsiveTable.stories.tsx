import type { Meta, StoryObj } from "@storybook/react-vite";
import { type RunStatus, StatusPill } from "./StatusPill";
import { ResponsiveTable } from "./ResponsiveTable";

const RUNS: { id: string; name: string; status: RunStatus }[] = [
  { id: "r1", name: "emitkit generate", status: "SUCCESS" },
  { id: "r2", name: "emitkit publish", status: "FAILED" },
];

const meta = { title: "UI/ResponsiveTable", component: ResponsiveTable } satisfies Meta<typeof ResponsiveTable>;

export default meta;
// Generic component: see DataList.stories.tsx.
type Story = StoryObj;

const pill = (status: RunStatus) => <StatusPill status={status} />;

/** A table from 768px up, cards below. Resize the canvas to see the switch. */
export const Default: Story = {
  render: () => (
    <ResponsiveTable
      label="Recent runs"
      columns={[
        { key: "name", header: "Run", cell: (run) => run.name },
        { key: "status", header: "Status", cell: (run) => pill(run.status) },
      ]}
      rows={RUNS}
      rowKey={(run) => run.id}
      renderCard={(run) => (
        <div className="flex items-center justify-between gap-2"><span className="font-medium">{run.name}</span>{pill(run.status)}</div>
      )}
    />
  ),
};
