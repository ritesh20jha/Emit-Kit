import type { Meta, StoryObj } from "@storybook/react-vite";
import { type ReactNode, useEffect } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { Checkbox } from "./Checkbox";
import { FormField } from "./FormField";
import { Input } from "./Input";
import { Select } from "./Select";
import { Switch } from "./Switch";
import { Textarea } from "./Textarea";

/** Stands in for the page's form: a react-hook-form context with optional errors already set. */
function FormDemo({ errors = {}, children }: { errors?: Record<string, string>; children: ReactNode }) {
  const methods = useForm<Record<string, unknown>>({
    defaultValues: { pypiName: "", region: "eu", notes: "", agree: false, notify: true },
  });
  useEffect(() => {
    for (const [name, message] of Object.entries(errors)) methods.setError(name, { message });
  }, [methods, errors]);
  return (
    <FormProvider {...methods}>
      <div className="flex w-80 flex-col gap-4">{children}</div>
    </FormProvider>
  );
}

const meta = {
  title: "UI/FormField",
  component: FormField,
  args: { name: "pypiName", label: "PyPI name", children: <Input /> },
  render: (args) => (
    <FormDemo>
      <FormField {...args} />
    </FormDemo>
  ),
} satisfies Meta<typeof FormField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: { description: "Published under this name on PyPI." },
};

export const WithError: Story = {
  render: (args) => (
    <FormDemo errors={{ pypiName: "Name is already taken" }}>
      <FormField {...args} description="Published under this name on PyPI." />
    </FormDemo>
  ),
};

export const AllControls: Story = {
  render: () => (
    <FormDemo>
      <FormField name="pypiName" label="PyPI name" description="Published under this name on PyPI.">
        <Input placeholder="emitkit-sdk" />
      </FormField>
      <FormField name="region" label="Region">
        <Select
          options={[
            { value: "eu", label: "Europe" },
            { value: "us", label: "United States" },
          ]}
        />
      </FormField>
      <FormField name="notes" label="Release notes">
        <Textarea placeholder="What changed?" />
      </FormField>
      <FormField name="agree" label="Accept the terms">
        <Checkbox />
      </FormField>
      <FormField name="notify" label="Notify on failure">
        <Switch />
      </FormField>
    </FormDemo>
  ),
};
