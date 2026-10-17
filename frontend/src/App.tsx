import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { NavLink, Route, Routes, useLocation, Link } from 'react-router-dom';
import {
  ArrowUpRight,
  BookOpen,
  CalendarClock,
  ChevronLeft,
  CircleDot,
  Coins,
  FlaskConical,
  LayoutDashboard,
  KeyRound,
  ListTodo,
  Menu,
  Moon,
  Radar,
  Plus,
  Settings2,
  Sun,
} from 'lucide-react';
import { useSystem } from './queries';
import Overview from './pages/Overview';
import Tasks, { TaskDetail } from './pages/Tasks';
const Knowledge = lazy(() => import('./pages/Knowledge'));
const Secrets = lazy(() => import('./pages/Secrets'));
const Schedules = lazy(() => import('./pages/Schedules'));
const Monitoring = lazy(() => import('./pages/Monitoring'));
const Playground = lazy(() => import('./pages/Playground'));
const System = lazy(() => import('./pages/System'));
const Usage = lazy(() => import('./pages/Usage'));
import { EmptyState, Loading, setApplicationTimeZone } from './components';

const navigation = [
  { to: '/', name: 'Overview', icon: LayoutDashboard },
  { to: '/tasks', name: 'Tasks', icon: ListTodo },
  { to: '/usage', name: 'AI Usage', icon: Coins },
  { to: '/knowledge', name: 'Knowledge', icon: BookOpen },
  { to: '/secrets', name: 'Secrets', icon: KeyRound },
  { to: '/schedules', name: 'Schedules', icon: CalendarClock },
  { to: '/monitoring', name: 'Monitoring', icon: Radar },
  { to: '/playground', name: 'Inference Playground', icon: FlaskConical },
];
export default function App() {
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('velocity.sidebar') === 'collapsed');
  const [mobile, setMobile] = useState(false);
  const [narrow, setNarrow] = useState(() => window.matchMedia?.('(max-width: 760px)').matches ?? false);
  const sidebar = useRef<HTMLElement>(null);
  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 760px)');
    if (!media) return;
    const update = () => setNarrow(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    if (!mobile || !narrow) return;
    const opener = document.activeElement as HTMLElement | null;
    sidebar.current?.querySelector<HTMLElement>('a')?.focus();
    return () => opener?.focus();
  }, [mobile, narrow]);
  const [theme, setTheme] = useState(
    () =>
      localStorage.getItem('velocity.theme') ??
      (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
  );
  const location = useLocation();
  const system = useSystem();
  const timezone = system.data?.timezone;
  useEffect(() => {
    setApplicationTimeZone(timezone);
  }, [timezone]);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('velocity.theme', theme);
  }, [theme]);
  useEffect(() => {
    localStorage.setItem('velocity.sidebar', collapsed ? 'collapsed' : 'expanded');
  }, [collapsed]);
  useEffect(() => {
    setMobile(false);
    document.title = `Velocity · ${navigation.find((n) => (n.to === '/' ? location.pathname === '/' : location.pathname.startsWith(n.to)))?.name ?? 'System'}`;
  }, [location.pathname]);
  return (
    <div
      className={`app ${collapsed ? 'sidebar-collapsed' : ''} ${mobile ? 'mobile-open' : ''}`}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && mobile) setMobile(false);
      }}
    >
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      {mobile && (
        <button className="sidebar-scrim" aria-label="Close navigation" onClick={() => setMobile(false)} />
      )}
      <aside
        ref={sidebar}
        id="main-navigation"
        className="sidebar"
        aria-label="Main navigation"
        inert={narrow && !mobile}
      >
        <Link className="brand" to="/" aria-label="Velocity overview">
          <span className="brand-mark">
            <CircleDot size={20} strokeWidth={2.5} />
          </span>
          <span className="nav-label">velocity</span>
        </Link>
        <Link className="button primary new-task" to="/tasks?new=1" aria-label="New task">
          <Plus size={16} />
          <span className="nav-label">New task</span>
        </Link>
        <nav>
          {navigation.map(({ to, name, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              title={name}
              aria-label={name}
              className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
            >
              <Icon size={17} />
              <span className="nav-label">{name}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <NavLink
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
            to="/system"
            title="Integrations & System"
            aria-label="Integrations & System"
          >
            <Settings2 size={17} />
            <span className="nav-label">Integrations & System</span>
          </NavLink>
          <button
            type="button"
            className="nav-item"
            title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
            aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
          >
            {theme === 'dark' ? <Sun size={17} /> : <Moon size={17} />}
            <span className="nav-label">{theme === 'dark' ? 'Light theme' : 'Dark theme'}</span>
          </button>
          <div className="workspace-card">
            <span
              className={`connection-dot ${system.isError ? 'offline' : system.data ? 'online' : ''}`}
              title={system.isError ? 'Server disconnected' : system.data ? 'Connected' : 'Checking server'}
            />
            <span className="nav-label">
              {system.isError ? 'Server disconnected' : system.data ? 'Connected' : 'Checking server…'}
            </span>
            <button
              className="icon-button collapse-control"
              aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              onClick={() => setCollapsed(!collapsed)}
            >
              <ChevronLeft size={16} />
            </button>
          </div>
        </div>
      </aside>
      <div className="workspace" inert={narrow && mobile}>
        <div className="topbar">
          <button
            className="icon-button mobile-menu"
            aria-label="Open navigation"
            aria-expanded={mobile}
            aria-controls="main-navigation"
            onClick={() => setMobile(!mobile)}
          >
            <Menu size={20} />
          </button>
          <span>
            {navigation.find((n) =>
              n.to === '/' ? location.pathname === '/' : location.pathname.startsWith(n.to),
            )?.name ?? 'Integrations & System'}
          </span>
        </div>
        <main id="main" tabIndex={-1}>
          <Suspense fallback={<Loading />}>
            <Routes>
              <Route path="/" element={<Overview />} />
              <Route path="/tasks" element={<Tasks />} />
              <Route path="/tasks/:id" element={<TaskDetail />} />
              <Route path="/usage" element={<Usage />} />
              <Route path="/knowledge" element={<Knowledge />} />
              <Route path="/secrets" element={<Secrets />} />
              <Route path="/schedules" element={<Schedules />} />
              <Route path="/monitoring" element={<Monitoring />} />
              <Route path="/playground" element={<Playground />} />
              <Route path="/system" element={<System />} />
              <Route
                path="*"
                element={
                  <EmptyState
                    title="Page not found"
                    action={
                      <Link className="button" to="/">
                        Return to overview <ArrowUpRight size={15} />
                      </Link>
                    }
                  />
                }
              />
            </Routes>
          </Suspense>
        </main>
      </div>
    </div>
  );
}
