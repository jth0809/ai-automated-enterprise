export interface BackendStatus {
  service: string;
  status: string;
  database: string;
  timestamp: string;
}

export function StatusCard({ status }: { status: BackendStatus }) {
  return (
    <dl className="card">
      <div>
        <dt>Service</dt>
        <dd>{status.service}</dd>
      </div>
      <div>
        <dt>API</dt>
        <dd className={status.status === "UP" ? "up" : "down"}>
          {status.status}
        </dd>
      </div>
      <div>
        <dt>Database</dt>
        <dd className={status.database === "UP" ? "up" : "down"}>
          {status.database}
        </dd>
      </div>
      <div>
        <dt>Checked at</dt>
        <dd>{new Date(status.timestamp).toLocaleString()}</dd>
      </div>
    </dl>
  );
}
