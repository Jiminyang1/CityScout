const STATUS_CLASS: Record<number, string> = {
  [-1]: "status-error",
  0: "status-pending",
  1: "status-waiting",
  2: "status-paid",
  3: "status-paid",
  4: "status-cancelled",
  5: "status-pending",
  6: "status-cancelled"
};

export function StatusBadge({
  status,
  text
}: {
  status: number;
  text?: string;
}) {
  return (
    <span className={`status-badge ${STATUS_CLASS[status] || "status-pending"}`}>
      {text || "未知"}
    </span>
  );
}
