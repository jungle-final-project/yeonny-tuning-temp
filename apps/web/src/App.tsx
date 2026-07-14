import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AdminLoginPage, AuthCallbackPage, LoginPage, MyProfilePage, SignupPage } from './features/auth/AuthPages';
import { RequireAdmin } from './features/auth/RequireAdmin';
import { RequireUser } from './features/auth/RequireUser';
import { CheckoutPaymentPage, TossPointPaymentFailPage, TossPointPaymentSuccessPage } from './features/payment';
import { AllPartsPage, AssemblyRequestDetailPage, AssemblyRequestHistoryPage, CheckoutCompletePage, CheckoutOffersPage, CheckoutPage, PartDetailPage, SelfQuotePage } from './features/parts/PartsPages';
import { BuildResultPage, ChangePartPage, HomePage, LatestBuildResultPage, MyQuotesPage, RequirementPage } from './features/quote/QuotePages';
import { AsChatPage, SupportNewPage, SupportTicketPage } from './features/support/SupportPages';
import { SupportChatWidget } from './features/support/SupportChatWidget';
import { AdminAssemblyPage, AdminBuildGraphLayoutsPage, AdminDashboardPage, AdminLoadTestsPage, AdminPartsPage, AdminPriceJobsPage, AdminSupportChatSessionsPage, AdminTicketDetailPage, AdminTicketsPage, AgentSessionAdminPage, AgentSessionsListAdminPage, RagEvidenceAdminPage, RagEvidenceListAdminPage, ToolInvocationAdminPage, ToolInvocationsListAdminPage } from './features/admin/AdminPages';
import { AiBuildAssistant } from './features/quote/components/AiBuildAssistant';
import { TechnicianApplyPage, TechnicianDashboardPage, TechnicianJobsPage, TechnicianRequestDetailPage } from './features/technician/TechnicianPages';
import { getToken } from './lib/api';

export default function App() {
  return (
    <>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/requirements/new" element={<RequireUser><RequirementPage /></RequireUser>} />
        <Route path="/builds/latest" element={<RequireUser><LatestBuildResultPage /></RequireUser>} />
        <Route path="/builds/:buildId" element={<RequireUser><BuildResultPage /></RequireUser>} />
        <Route path="/self-quote" element={<RequireUser><SelfQuotePage /></RequireUser>} />
        <Route path="/checkout" element={<RequireUser><CheckoutPage /></RequireUser>} />
        <Route path="/checkout/offers/:requestId" element={<RequireUser><CheckoutOffersPage /></RequireUser>} />
        <Route path="/checkout/payment/:requestId" element={<RequireUser><CheckoutPaymentPage /></RequireUser>} />
        <Route path="/checkout/toss/success/:requestId" element={<RequireUser><TossPointPaymentSuccessPage /></RequireUser>} />
        <Route path="/checkout/toss/fail/:requestId" element={<RequireUser><TossPointPaymentFailPage /></RequireUser>} />
        <Route path="/checkout/complete/:requestId" element={<RequireUser><CheckoutCompletePage /></RequireUser>} />
        <Route path="/checkout/offers" element={<Navigate to="/my/assembly-requests" replace />} />
        <Route path="/checkout/complete" element={<Navigate to="/my/assembly-requests" replace />} />
        <Route path="/parts" element={<RequireUser><AllPartsPage /></RequireUser>} />
        <Route path="/parts/:partId" element={<RequireUser><PartDetailPage /></RequireUser>} />
        <Route path="/builds/:buildId/change-part" element={<RequireUser><ChangePartPage /></RequireUser>} />
        <Route path="/my/profile" element={<RequireUser><MyProfilePage /></RequireUser>} />
        <Route path="/my/quotes" element={<RequireUser><MyQuotesPage /></RequireUser>} />
        <Route path="/my/assembly-requests" element={<RequireUser><AssemblyRequestHistoryPage /></RequireUser>} />
        <Route path="/my/assembly-requests/:requestId" element={<RequireUser><AssemblyRequestDetailPage /></RequireUser>} />
        <Route path="/technician/apply" element={<RequireUser><TechnicianApplyPage /></RequireUser>} />
        <Route path="/technician" element={<RequireUser><TechnicianDashboardPage /></RequireUser>} />
        <Route path="/technician/jobs" element={<RequireUser><TechnicianJobsPage /></RequireUser>} />
        <Route path="/technician/requests/:requestId" element={<RequireUser><TechnicianRequestDetailPage /></RequireUser>} />
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
        <Route path="/admin/assembly" element={<RequireAdmin><AdminAssemblyPage /></RequireAdmin>} />
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
  if (!getToken() || pathname === '/login' || pathname === '/signup' || pathname === '/auth/callback' || pathname.startsWith('/admin') || pathname.startsWith('/technician') || pathname === '/support/new' || pathname === '/my/profile') {
    return null;
  }
  return <SupportChatWidget />;
}

function GlobalAiBuildAssistant() {
  const { pathname } = useLocation();
  if (!getToken() || pathname === '/login' || pathname === '/signup' || pathname === '/auth/callback' || pathname.startsWith('/admin') || pathname.startsWith('/technician') || pathname === '/self-quote' || pathname === '/my/profile') {
    return null;
  }
  return <AiBuildAssistant surface="home" />;
}
