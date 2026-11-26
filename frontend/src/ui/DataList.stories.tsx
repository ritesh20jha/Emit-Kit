import type { Meta, StoryObj } from "@storybook/react-vite";
import { DataList } from "./DataList";
import { type RunStatus, StatusPill } from "./StatusPill";

const RUNS: { id: string; name: string; status: RunStatus }[] = [
  { id: "r1", name: "emitkit generate", status: "SUCCESS" },
  { id: "r2", name: "emitkit publish", status: "FAILED" },
];

const meta = { title: "UI/DataList", component: DataList } satisfies Meta<typeof DataList>;

export default meta;
// Generic component: the row type is inferred inside render, so the story is not typed against the meta.
type Story = StoryObj;

export const Default: Story = {
  render: () => (
    <div className="max-w-sm">
      <DataList
        label="Recent runs"
        rows={RUNS}
        rowKey={(run) => run.id}
        renderCard={(run) => (
          <div className="flex items-center justify-between gap-2"><span className="font-medium">{run.name}</span><StatusPill status={run.status} /></div>
        )}
      />
    </div>
  ),
};
