import type { Meta, StoryObj } from "@storybook/react-vite";
import { useEffect } from "react";
import { Toaster } from "./Toaster";
import { toast } from "./toast";
import { ThemeContext } from "./theme/ThemeProvider";

/** Fires one success and one error toast on mount. Storybook has no ThemeProvider, so the decorator supplies one. */
function ToastDemo() {
  useEffect(() => {
    toast.success("Synced 12 repositories");
    toast.error({ code: "SPEC_CONFLICT", message: "Someone else changed this file." });
  }, []);
  return (
    <>
      <p className="text-sm text-fg-muted">Toasts appear at the bottom right.</p>
      <Toaster duration={Infinity} />
    </>
  );
}

const meta = {
  title: "UI/Toaster",
  component: Toaster,
  decorators: [
    (Story, context) => {
      const theme = context.globals.theme === "dark" ? "dark" : "light";
      return (
        <ThemeContext.Provider value={{ mode: theme, resolved: theme, setMode: () => {} }}>
          <Story />
        </ThemeContext.Provider>
      );
    },
  ],
} satisfies Meta<typeof Toaster>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Toasts: Story = {
  render: () => <ToastDemo />,
};
