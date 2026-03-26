// ─── ArtGrid Server Configuration ────────────────────────────────────────────
// Change this to your machine's local IP when you switch networks.
// Find it with: ipconfig (Windows) or ifconfig (Mac/Linux)
// Your phone must be on the same Wi-Fi network as this machine.

export const SERVER_IP   = '10.254.92.50';
export const SERVER_PORT = '8000';
export const SERVER_BASE = `http://${SERVER_IP}:${SERVER_PORT}`;

export const SERVER_URL  = `${SERVER_BASE}/process-image/`;
export const BODY_URL    = `${SERVER_BASE}/process-body/`;
export const RECALC_URL  = `${SERVER_BASE}/recalculate/`;
