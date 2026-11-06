import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { type ReactElement, type ReactNode, useEffect, useState } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { Checkbox } from "./Checkbox";
import { FormField } from "./FormField";
import { Input } from "./Input";
import { SecretField } from "./SecretField";
import { Select } from "./Select";
import { Switch } from "./Switch";

// Radix Checkbox measures itself with ResizeObserver, which jsdom does not implement.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver;

/** Renders a control inside a react-hook-form context, with errors applied the way a failed submit would. */
function renderForm(
  ui: ReactElement,
  { errors = {}, defaultValues }: { errors?: Record<string, string>; defaultValues?: Record<string, unknown> } = {},
) {
  function Wrapper({ children }: { children: ReactNode }) {
    const methods = useForm({ defaultValues });
    useEffect(() => {
      for (const [name, message] of Object.entries(errors)) methods.setError(name, { message });
    }, [methods]);
    return <FormProvider {...methods}>{children}</FormProvider>;
  }
  return render(ui, { wrapper: Wrapper });
}

test("error is linked to the input", async () => {
  renderForm(<FormField name="pypiName" label="PyPI name"><Input /></FormField>, { errors: { pypiName: "Required" } });
  const input = screen.getByLabelText("PyPI name");
  expect(input).toHaveAttribute("aria-invalid", "true");
  expect(input).toHaveAccessibleDescription(/Required/);
});

test("SecretField hides the value until Replace", async () => {
  render(<SecretField isSet label="AI key" />);
  expect(screen.queryByLabelText("AI key")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Replace" }));
  expect(screen.getByLabelText("AI key")).toHaveFocus();
});

test("Switch toggles with Space", async () => {
  render(<Switch aria-label="Notify" />);
  const toggle = screen.getByRole("switch", { name: "Notify" });
  expect(toggle).toHaveAttribute("aria-checked", "false");
  toggle.focus();
  await userEvent.keyboard(" ");
  expect(toggle).toHaveAttribute("aria-checked", "true");
});

test("FormField description is the accessible description when there is no error", () => {
  renderForm(
    <FormField name="pypiName" label="PyPI name" description="Used when publishing"><Input /></FormField>,
  );
  expect(screen.getByLabelText("PyPI name")).toHaveAccessibleDescription("Used when publishing");
});

test("Input inside FormField writes its value into the form", async () => {
  renderForm(<FormField name="pypiName" label="PyPI name"><Input /></FormField>);
  await userEvent.type(screen.getByLabelText("PyPI name"), "emitkit");
  expect(screen.getByLabelText("PyPI name")).toHaveValue("emitkit");
});

test("Select shows the selected option and is labelled by the FormField", () => {
  renderForm(
    <FormField name="region" label="Region">
      <Select
        options={[
          { value: "eu", label: "Europe" },
          { value: "us", label: "United States" },
        ]}
      />
    </FormField>,
    { defaultValues: { region: "eu" } },
  );
  expect(screen.getByRole("combobox", { name: "Region" })).toHaveTextContent("Europe");
});

test("Checkbox and Switch keep the global focus ring", () => {
  render(
    <>
      <Checkbox aria-label="Agree" />
      <Switch aria-label="Notify" />
    </>,
  );
  // app.css draws the focus ring with :focus-visible; outline-none on these roots would hide it (WCAG 2.4.7).
  expect(screen.getByRole("checkbox").className).not.toMatch(/\boutline-none\b/);
  expect(screen.getByRole("switch").className).not.toMatch(/\boutline-none\b/);
});

test("SecretField takes typing when uncontrolled", async () => {
  render(<SecretField isSet={false} label="AI key" />);
  await userEvent.type(screen.getByLabelText("AI key"), "sk-123");
  expect(screen.getByLabelText("AI key")).toHaveValue("sk-123");
});

test("SecretField takes typing through value and onChange when controlled", async () => {
  function Controlled() {
    const [value, setValue] = useState<string | null>(null);
    return <SecretField isSet={false} label="AI key" value={value} onChange={setValue} />;
  }
  render(<Controlled />);
  await userEvent.type(screen.getByLabelText("AI key"), "sk-123");
  expect(screen.getByLabelText("AI key")).toHaveValue("sk-123");
});

test("SecretField follows a controlled replacing flag and returns to Key set when it clears", () => {
  const { rerender } = render(<SecretField isSet label="AI key" replacing />);
  expect(screen.getByLabelText("AI key")).toHaveFocus();
  rerender(<SecretField isSet label="AI key" replacing={false} />);
  expect(screen.getByText("Key set")).toBeInTheDocument();
  expect(screen.queryByLabelText("AI key")).not.toBeInTheDocument();
});

test("SecretField Replace and Cancel report through onReplacingChange, onReplace and onChange", async () => {
  const onReplace = vi.fn();
  const onReplacingChange = vi.fn();
  const onChange = vi.fn();
  const { rerender } = render(
    <SecretField isSet label="AI key" replacing={false} onReplace={onReplace} onReplacingChange={onReplacingChange} onChange={onChange} />,
  );
  await userEvent.click(screen.getByRole("button", { name: "Replace" }));
  expect(onReplace).toHaveBeenCalledOnce();
  expect(onReplacingChange).toHaveBeenLastCalledWith(true);
  rerender(
    <SecretField isSet label="AI key" replacing onReplace={onReplace} onReplacingChange={onReplacingChange} onChange={onChange} />,
  );
  await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(onChange).toHaveBeenLastCalledWith(null);
  expect(onReplacingChange).toHaveBeenLastCalledWith(false);
});

test("Checkbox inside FormField toggles", async () => {
  renderForm(<FormField name="agree" label="Accept terms"><Checkbox /></FormField>, { defaultValues: { agree: false } });
  const box = screen.getByRole("checkbox", { name: "Accept terms" });
  expect(box).toHaveAttribute("aria-checked", "false");
  await userEvent.click(box);
  expect(box).toHaveAttribute("aria-checked", "true");
});
