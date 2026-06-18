import HistoryApp from "./HistoryApp";
import TrayPopover from "./TrayPopover";

export default function App() {
  const view = new URLSearchParams(window.location.search).get("view");

  if (view === "tray") {
    return <TrayPopover />;
  }

  return <HistoryApp />;
}
