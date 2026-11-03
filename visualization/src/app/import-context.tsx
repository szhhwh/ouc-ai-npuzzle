import { createContext, useContext } from "react";

/** Opens the system file picker of the page-wide import dropzone. */
export const ImportPickerContext = createContext<() => void>(() => {});

export const useImportPicker = () => useContext(ImportPickerContext);
