import type { Meta, StoryObj } from "@storybook/react-vite";
import { StatusPill } from "./StatusPill";
import { TBody, Table, Td, Th, THead, Tr } from "./Table";

const meta = { title: "UI/Table", component: Table, args: { label: "Recent runs" } } satisfies Meta<typeof Table>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  render: (args) => (
    <Table {...args}>
      <THead>
        <Tr><Th>Run</Th><Th>Status</Th><Th className="text-right">Duration</Th></Tr>
      </THead>
      <TBody>
        <Tr><Td>emitkit generate</Td><Td><StatusPill status="SUCCESS" /></Td><Td className="text-right tabular-nums">12.4 s</Td></Tr>
        <Tr><Td>emitkit publish</Td><Td><StatusPill status="FAILED" /></Td><Td className="text-right tabular-nums">3.1 s</Td></Tr>
      </TBody>
    </Table>
  ),
};
