import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AdminLoginPage, AuthCallbackPage, LoginPage, SignupPage } from './features/auth/AuthPages';
import { RequireAdmin } from './features/auth/RequireAdmin';
import { RequireUser } from './features/auth/RequireUser';
import { AllPartsPage, CheckoutCompletePage, CheckoutPage, PartDetailPage, SelfQuotePage } from './features/parts/PartsPages';
import { BuildResultPage, ChangePartPage, HomePage, LatestBuildResultPage, MyQuotesPage, RequirementPage } from './features/quote/QuotePages';
import { AsChatPage, SupportNewPage, SupportTicketPage } from './features/support/SupportPages';
import { SupportChatWidget } from './features/support/SupportChatWidget';
import { AdminBuildGraphLayoutsPage, AdminDashboardPage, AdminLoadTestsPage, AdminPartsPage, AdminPriceJobsPage, AdminSupportChatSessionsPage, AdminTicketDetailPage, AdminTicketsPage, AgentSessionAdminPage, AgentSessionsListAdminPage, RagEvidenceAdminPage, RagEvidenceListAdminPage, ToolInvocationAdminPage, ToolInvocationsListAdminPage } from './features/admin/AdminPages';
import { AiBuildAssistant } from './features/quote/components/AiBuildAssistant';

export default function App() {
  return (
    <>
      <Routes>
        <Route path="/" element={<RequireUser preserveRedirect={false}><HomePage /></RequireUser>} />
        <Route path="/requirements/new" element={<RequireUser><RequirementPage /></RequireUser>} />
        <Route path="/builds/latest" element={<RequireUser><LatestBuildResultPage /></RequireUser>} />
        <Route path="/builds/:buildId" element={<RequireUser><BuildResultPage /></RequireUser>} />
        <Route path="/self-quote" element={<RequireUser><SelfQuotePage /></RequireUser>} />
        <Route path="/checkout" element={<RequireUser><CheckoutPage /></RequireUser>} />
        <Route path="/checkout/complete" element={<RequireUser><CheckoutCompletePage /></RequireUser>} />
        <Route path="/parts" element={<RequireUser><AllPartsPage /></RequireUser>} />
        <Route path="/parts/:partId" element={<RequireUser><PartDetailPage /></RequireUser>} />
        <Route path="/builds/:buildId/change-part" element={<RequireUser><ChangePartPage /></RequireUser>} />
        <Route path="/my/quotes" element={<RequireUser><MyQuotesPage /></RequireUser>} />
        <Route path="/support/new" element={<RequireUser><SupportNewPage /></RequireUser>} />
        <Route path="/support/ai-chat" element={<RequireUser><AsChatPage /></RequireUser>} />
        <Route path="/support/:ticketId" element={<RequireUser><SupportTicketPage /></RequireUser>} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="/admin/login" element={<AdminLoginPage />} />
        <Route path="/admin" element={<RequireAdmin><AdminDashboardPage /></RequireAdmin>} />
        <Route path="/admin/agent-sessions" element={<RequireAdmin><AgentSessionsListAdminPage /></RequireAdmin>} />
        <Route path="/admin/agent-sessions/:id" element={<RequireAdmin><AgentSessionAdminPage /></RequireAdmin>} />
        <Route path="/admin/tool-invocations" element={<RequireAdmin><ToolInvocationsListAdminPage /></RequireAdmin>} />
        <Route path="/admin/tool-invocations/:id" element={<RequireAdmin><ToolInvocationAdminPage /></RequireAdmin>} />
        <Route path="/admin/rag-evidence" element={<RequireAdmin><RagEvidenceListAdminPage /></RequireAdmin>} />
        <Route path="/admin/rag-evidence/:id" element={<RequireAdmin><RagEvidenceAdminPage /></RequireAdmin>} />
        <Route path="/admin/parts" element={<RequireAdmin><AdminPartsPage /></RequireAdmin>} />
        <Route path="/admin/price-jobs" element={<RequireAdmin><AdminPriceJobsPage /></RequireAdmin>} />
        <Route path="/admin/build-graph-layouts" element={<RequireAdmin><AdminBuildGraphLayoutsPage /></RequireAdmin>} />
        <Route path="/admin/load-tests" element={<RequireAdmin><AdminLoadTestsPage /></RequireAdmin>} />
        <Route path="/admin/support-chat-sessions" element={<RequireAdmin><AdminSupportChatSessionsPage /></RequireAdmin>} />
        <Route path="/admin/as-tickets" element={<RequireAdmin><AdminTicketsPage /></RequireAdmin>} />
        <Route path="/admin/as-tickets/:ticketId" element={<RequireAdmin><AdminTicketDetailPage /></RequireAdmin>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <GlobalSupportChatWidget />
      <GlobalAiBuildAssistant />
    </>
  );
}

function GlobalSupportChatWidget() {
  const { pathname } = useLocation();
  if (pathname === '/login' || pathname === '/signup' || pathname === '/auth/callback' || pathname.startsWith('/admin') || pathname === '/support/new') {
    return null;
  }
  return <SupportChatWidget />;
}

function GlobalAiBuildAssistant() {
  const { pathname } = useLocation();
  if (pathname === '/login' || pathname === '/signup' || pathname === '/auth/callback' || pathname.startsWith('/admin') || pathname === '/self-quote') {
    return null;
  }
  return <AiBuildAssistant surface="home" />;
}
