import { cn } from "./cn";

test("token radius classes resolve against Tailwind radius utilities", () => {
  expect(cn("rounded-control", "rounded-none")).toBe("rounded-none");
  expect(cn("rounded-card", "rounded-control")).toBe("rounded-control");
});
