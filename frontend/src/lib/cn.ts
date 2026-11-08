import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Merges class names, with later Tailwind utilities winning over earlier ones.
 *
 * `clsx` handles the conditionals; `twMerge` resolves conflicts. Without the
 * second step, a component's `className` prop cannot reliably override a default
 * — `"px-4"` and `"px-2"` would both end up in the attribute and the result
 * would depend on stylesheet order rather than on the caller's intent.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
