/** Library-owned display naming; the export protocol never carries a record name. */
export const DEFAULT_NAME = "Untitled Problem";

export const nameOf = (name: unknown) =>
  typeof name === "string" && name.trim() ? name.trim() : DEFAULT_NAME;
