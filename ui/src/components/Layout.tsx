import { NavLink, Outlet } from "react-router-dom";

interface NavItem {
  to: string;
  label: string;
  end?: true;
}

const navItems: NavItem[] = [
  { to: "/", label: "Dashboard", end: true },
  { to: "/orders", label: "Orders" },
  { to: "/market-data", label: "Market Data" },
  { to: "/rfq", label: "RFQ" },
  { to: "/strategies", label: "Strategies" },
  { to: "/analytics", label: "Analytics" },
  { to: "/reconciliation", label: "Reconciliation" },
];

export default function Layout() {
  return (
    <div className="flex min-h-screen">
      <nav className="w-52 shrink-0 bg-white border-r border-slate-200 flex flex-col">
        <div className="px-4 py-5 border-b border-slate-200">
          <span className="text-lg font-bold tracking-tight text-slate-900">MariaAlpha</span>
        </div>
        <ul className="flex-1 py-2">
          {navItems.map(({ to, label, end }) => (
            <li key={to}>
              <NavLink
                to={to}
                {...(end ? { end } : {})}
                className={({ isActive }) =>
                  `block px-4 py-2 text-sm rounded mx-2 my-0.5 ${
                    isActive
                      ? "bg-slate-200 text-slate-900 font-medium"
                      : "text-slate-700 hover:bg-slate-100"
                  }`
                }
              >
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <main className="flex-1 overflow-auto bg-slate-50">
        <Outlet />
      </main>
    </div>
  );
}
