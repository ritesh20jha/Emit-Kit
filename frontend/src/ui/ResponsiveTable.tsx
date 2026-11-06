import type { ReactNode } from "react";
import { DataList } from "./DataList";
import { TBody, Table, Td, Th, THead, Tr } from "./Table";
import { useMediaQuery } from "./useMediaQuery";

export interface ResponsiveColumn<T> {
  key: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
}

export interface ResponsiveTableProps<T> {
  /** Accessible name, shared by the table and the card list. */
  label: string;
  columns: ResponsiveColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** The card for one row below 768px. */
  renderCard: (row: T) => ReactNode;
}

/** A Table from 768px up, a DataList of cards below. */
export function ResponsiveTable<T>({ label, columns, rows, rowKey, renderCard }: ResponsiveTableProps<T>) {
  const wide = useMediaQuery("(min-width: 768px)");
  if (!wide) return <DataList label={label} rows={rows} rowKey={rowKey} renderCard={renderCard} />;
  return (
    <Table label={label}>
      <THead>
        <Tr>
          {columns.map((column) => <Th key={column.key}>{column.header}</Th>)}
        </Tr>
      </THead>
      <TBody>
        {rows.map((row) => (
          <Tr key={rowKey(row)}>{columns.map((column) => <Td key={column.key}>{column.cell(row)}</Td>)}</Tr>
        ))}
      </TBody>
    </Table>
  );
}
