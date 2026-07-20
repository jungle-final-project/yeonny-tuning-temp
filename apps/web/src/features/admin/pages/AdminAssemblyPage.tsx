import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardList, Plus, RotateCcw, Save, Trash2, UserRoundCog, XCircle } from 'lucide-react';
import { useEffect, useState } from 'react';
import { AdminShell, StateMessage, StatusBadge } from '../../../components/ui';
import {
  createAdminAssemblyOffer,
  createAdminTechnician,
  approveAdminTechnician,
  deleteAdminTechnician,
  getAdminAssemblyRequest,
  listAdminAssemblyRequests,
  listAdminTechnicians,
  restoreAdminTechnician,
  rejectAdminTechnician,
  updateAdminAssemblyOffer,
  updateAdminAssemblyRequestStatus,
  updateAdminTechnician,
  withdrawAdminAssemblyOffer,
  type AssemblyOffer,
  type AssemblyRequest,
  type AssemblyRequestStatus,
  type Technician,
  type TechnicianPayload
} from '../../parts/assemblyApi';

const REGIONS = ['서울', '경기', '인천', '대전', '대구', '부산', '광주'];
const REQUEST_STATUSES: AssemblyRequestStatus[] = ['REQUESTED', 'OFFERED', 'MATCHED', 'CONFIRMED', 'ASSEMBLING', 'SHIPPED', 'COMPLETED', 'CANCELLED'];

export function AdminAssemblyPage() {
  const [tab, setTab] = useState<'requests' | 'technicians'>('requests');
  return (
    <AdminShell title="조립 중개 운영">
      <div className="mb-5 flex gap-2 border-b border-slate-200">
        <TabButton active={tab === 'requests'} onClick={() => setTab('requests')} icon={<ClipboardList size={16} />} label="요청 관리" />
        <TabButton active={tab === 'technicians'} onClick={() => setTab('technicians')} icon={<UserRoundCog size={16} />} label="기사 관리" />
      </div>
      {tab === 'requests' ? <AssemblyRequestsAdmin /> : <TechniciansAdmin />}
    </AdminShell>
  );
}

function AssemblyRequestsAdmin() {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState('');
  const [region, setRegion] = useState('');
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [statusNote, setStatusNote] = useState('');
  const [selectedTechnicianId, setSelectedTechnicianId] = useState('');
  const requestsQuery = useQuery({
    queryKey: ['admin-assembly-requests', status, region, query],
    queryFn: () => listAdminAssemblyRequests({ status, region, q: query })
  });
  const detailQuery = useQuery({
    queryKey: ['admin-assembly-request', selectedId],
    queryFn: () => getAdminAssemblyRequest(selectedId!),
    enabled: Boolean(selectedId)
  });
  const techniciansQuery = useQuery({
    queryKey: ['admin-technicians', 'assembly-offer'],
    queryFn: () => listAdminTechnicians({ status: 'ACTIVE' })
  });
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['admin-assembly-requests'] });
    queryClient.invalidateQueries({ queryKey: ['admin-assembly-request', selectedId] });
  };
  const statusMutation = useMutation({
    mutationFn: ({ target, note }: { target: AssemblyRequestStatus; note?: string }) => updateAdminAssemblyRequestStatus(selectedId!, target, note),
    onSuccess: () => { setStatusNote(''); refresh(); }
  });
  const createOfferMutation = useMutation({
    mutationFn: () => createAdminAssemblyOffer(selectedId!, selectedTechnicianId),
    onSuccess: () => { setSelectedTechnicianId(''); refresh(); }
  });
  const updateOfferMutation = useMutation({
    mutationFn: ({ offerId, payload }: { offerId: string; payload: Parameters<typeof updateAdminAssemblyOffer>[2] }) => updateAdminAssemblyOffer(selectedId!, offerId, payload),
    onSuccess: refresh
  });
  const withdrawOfferMutation = useMutation({
    mutationFn: ({ offerId, reason }: { offerId: string; reason: string }) => withdrawAdminAssemblyOffer(selectedId!, offerId, reason),
    onSuccess: refresh
  });
  const request = detailQuery.data;
  const nextStatus = request ? nextOperationalStatus(request.status) : null;
  const existingTechnicians = new Set(request?.offers.map((offer) => offer.technicianId) ?? []);
  const availableTechnicians = techniciansQuery.data?.items.filter((technician) => !existingTechnicians.has(technician.id)) ?? [];

  return (
    <div className="space-y-4">
      <section className="rounded-lg border border-slate-200 bg-white p-4">
        <div className="grid gap-3 md:grid-cols-4">
          <AdminInput label="검색" value={query} onChange={setQuery} placeholder="요청번호·사용자" />
          <AdminSelect label="상태" value={status} onChange={setStatus} options={['', ...REQUEST_STATUSES]} />
          <AdminSelect label="지역" value={region} onChange={setRegion} options={['', ...REGIONS]} />
          <div className="flex items-end"><div className="rounded bg-slate-100 px-3 py-2 text-xs font-bold text-slate-600">총 {requestsQuery.data?.total ?? 0}건</div></div>
        </div>
      </section>
      <div className="grid min-h-[620px] gap-4 xl:grid-cols-[minmax(0,1fr)_440px]">
        <section className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          {requestsQuery.isLoading ? <AdminLoading /> : requestsQuery.isError ? <AdminError /> : (
            <div className="overflow-x-auto"><table className="w-full text-left text-xs"><thead className="bg-slate-50 text-slate-500"><tr><Th>요청번호</Th><Th>사용자</Th><Th>지역/일정</Th><Th>금액</Th><Th>상태</Th></tr></thead><tbody>{requestsQuery.data?.items.map((item) => <tr key={item.id} onClick={() => setSelectedId(item.id)} className={`cursor-pointer border-t border-slate-100 hover:bg-blue-50 ${selectedId === item.id ? 'bg-blue-50' : ''}`}><Td strong>{item.requestNo}</Td><Td>{item.userEmail ?? '-'}</Td><Td>{item.region}<br />{item.preferredDate}</Td><Td>{(item.finalPrice ?? item.estimatedPartsPrice).toLocaleString()}원</Td><Td><StatusBadge status={item.status} /></Td></tr>)}</tbody></table></div>
          )}
        </section>
        <aside className="rounded-lg border border-slate-200 bg-white p-5">
          {!selectedId ? <EmptyAdminDetail text="관리할 조립 요청을 선택하세요." /> : detailQuery.isLoading ? <AdminLoading /> : detailQuery.isError || !request ? <AdminError /> : (
            <div className="space-y-5">
              <div><div className="flex flex-wrap items-center gap-2"><h2 className="text-lg font-black text-slate-950">{request.requestNo}</h2><StatusBadge status={request.status} /></div><p className="mt-1 text-xs font-bold text-slate-500">{request.region} · {request.preferredDate} · 예상가 {request.estimatedPartsPrice.toLocaleString()}원</p></div>
              <section className="rounded-md border border-slate-200 p-3"><div className="text-xs font-black text-slate-500">운영 상태 전환</div><textarea value={statusNote} onChange={(event) => setStatusNote(event.target.value)} rows={2} maxLength={1000} placeholder="상태 변경 메모 또는 취소 사유" className="mt-2 w-full rounded border border-slate-200 px-3 py-2 text-xs" /><div className="mt-2 flex gap-2">{nextStatus ? <button type="button" disabled={statusMutation.isPending} onClick={() => statusMutation.mutate({ target: nextStatus, note: statusNote.trim() || undefined })} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white">{nextStatus} 전환</button> : null}{!['COMPLETED', 'CANCELLED'].includes(request.status) ? <button type="button" disabled={!statusNote.trim() || statusMutation.isPending} onClick={() => statusMutation.mutate({ target: 'CANCELLED', note: statusNote.trim() })} className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs font-black text-red-700">요청 취소</button> : null}</div>{statusMutation.isError ? <AdminMutationError error={statusMutation.error} /> : null}</section>
              <section><div className="mb-2 flex items-center justify-between"><h3 className="text-sm font-black text-slate-900">기사 제안</h3><span className="text-xs font-bold text-slate-500">{request.offers.length}건</span></div><div className="space-y-2">{request.offers.map((offer) => <AdminOfferCard key={offer.id} offer={offer} editable={offer.status === 'AVAILABLE'} onSave={(payload) => updateOfferMutation.mutate({ offerId: offer.id, payload })} onWithdraw={(reason) => withdrawOfferMutation.mutate({ offerId: offer.id, reason })} />)}</div></section>
              {['REQUESTED', 'OFFERED'].includes(request.status) && availableTechnicians.length ? <section className="rounded-md border border-dashed border-slate-300 p-3"><div className="text-xs font-black text-slate-600">기사 제안 추가</div><div className="mt-2 flex gap-2"><select value={selectedTechnicianId} onChange={(event) => setSelectedTechnicianId(event.target.value)} className="min-w-0 flex-1 rounded border border-slate-200 px-2 text-xs"><option value="">기사 선택</option>{availableTechnicians.map((technician) => <option key={technician.id} value={technician.id}>{technician.displayName}</option>)}</select><button type="button" disabled={!selectedTechnicianId || createOfferMutation.isPending} onClick={() => createOfferMutation.mutate()} className="rounded bg-slate-950 px-3 py-2 text-xs font-black text-white">추가</button></div></section> : null}
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function AdminOfferCard({ offer, editable, onSave, onWithdraw }: { offer: AssemblyOffer; editable: boolean; onSave: (payload: Parameters<typeof updateAdminAssemblyOffer>[2]) => void; onWithdraw: (reason: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [partsPrice, setPartsPrice] = useState(String(offer.confirmedPartsPrice));
  const [assemblyFee, setAssemblyFee] = useState(String(offer.assemblyFee));
  const [deliveryFee, setDeliveryFee] = useState(String(offer.deliveryFee));
  const [leadTime, setLeadTime] = useState(String(offer.leadTimeDays));
  const [stockStatus, setStockStatus] = useState(offer.stockStatus);
  return <div className="rounded-md border border-slate-200 p-3"><div className="flex items-start justify-between gap-3"><div><div className="font-black text-slate-900">{offer.technicianName}</div><div className="mt-1 text-[11px] font-bold text-slate-500">최종 {offer.finalPrice.toLocaleString()}원 · {offer.leadTimeDays}일</div></div><StatusBadge status={offer.status} /></div>{editing ? <div className="mt-3 grid gap-2 sm:grid-cols-2"><MiniNumber label="부품 확인가" value={partsPrice} onChange={setPartsPrice} /><MiniNumber label="조립비" value={assemblyFee} onChange={setAssemblyFee} /><MiniNumber label="배송비" value={deliveryFee} onChange={setDeliveryFee} /><MiniNumber label="소요일" value={leadTime} onChange={setLeadTime} /><label className="col-span-full text-[11px] font-bold text-slate-500">재고 문구<input value={stockStatus} onChange={(event) => setStockStatus(event.target.value)} className="mt-1 h-9 w-full rounded border border-slate-200 px-2 text-xs" /></label><div className="col-span-full flex gap-2"><button type="button" onClick={() => { onSave({ confirmedPartsPrice: Number(partsPrice), assemblyFee: Number(assemblyFee), deliveryFee: Number(deliveryFee), leadTimeDays: Number(leadTime), stockStatus }); setEditing(false); }} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white"><Save size={13} className="mr-1 inline" />저장</button><button type="button" onClick={() => setEditing(false)} className="rounded border border-slate-200 px-3 py-2 text-xs font-black">취소</button></div></div> : editable ? <div className="mt-3 flex gap-2"><button type="button" onClick={() => setEditing(true)} className="rounded border border-slate-200 px-3 py-1.5 text-xs font-black">제안 보정</button><button type="button" onClick={() => { const reason = window.prompt('제안 철회 사유를 입력하세요.'); if (reason?.trim()) onWithdraw(reason.trim()); }} className="rounded border border-red-200 px-3 py-1.5 text-xs font-black text-red-700"><XCircle size={13} className="mr-1 inline" />철회</button></div> : null}</div>;
}

function TechniciansAdmin() {
  const queryClient = useQueryClient();
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [region, setRegion] = useState('');
  const [providerType, setProviderType] = useState('');
  const [verificationStatus, setVerificationStatus] = useState('');
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [newMode, setNewMode] = useState(false);
  const techniciansQuery = useQuery({ queryKey: ['admin-technicians', query, status, region, providerType, verificationStatus, includeDeleted], queryFn: () => listAdminTechnicians({ q: query, status, region, providerType, verificationStatus, includeDeleted }) });
  const pendingQuery = useQuery({ queryKey: ['admin-technicians', 'pending-count'], queryFn: () => listAdminTechnicians({ providerType: 'EXTERNAL', verificationStatus: 'PENDING' }) });
  const selected = techniciansQuery.data?.items.find((item) => item.id === selectedId) ?? null;
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin-technicians'] });
  const saveMutation = useMutation({ mutationFn: ({ id, payload }: { id?: string; payload: TechnicianPayload }) => id ? updateAdminTechnician(id, payload) : createAdminTechnician(payload), onSuccess: (saved) => { setSelectedId(saved.id); setNewMode(false); refresh(); } });
  const deleteMutation = useMutation({ mutationFn: deleteAdminTechnician, onSuccess: () => { setSelectedId(null); refresh(); } });
  const restoreMutation = useMutation({ mutationFn: restoreAdminTechnician, onSuccess: refresh });
  const approveMutation = useMutation({ mutationFn: approveAdminTechnician, onSuccess: refresh });
  const rejectMutation = useMutation({ mutationFn: ({ id, reason }: { id: string; reason: string }) => rejectAdminTechnician(id, reason), onSuccess: refresh });
  return <div className="space-y-4"><section className="rounded-lg border border-slate-200 bg-white p-4"><div className="grid gap-3 md:grid-cols-2 xl:grid-cols-7"><AdminInput label="검색" value={query} onChange={setQuery} placeholder="기사명·전문 분야" /><AdminSelect label="상태" value={status} onChange={setStatus} options={['', 'ACTIVE', 'INACTIVE', 'SUSPENDED']} /><AdminSelect label="기사 유형" value={providerType} onChange={setProviderType} options={['', 'INTERNAL', 'EXTERNAL']} /><AdminSelect label="검증 상태" value={verificationStatus} onChange={setVerificationStatus} options={['', 'PENDING', 'APPROVED', 'REJECTED']} /><AdminSelect label="지역" value={region} onChange={setRegion} options={['', ...REGIONS]} /><label className="flex items-end gap-2 pb-2 text-xs font-black text-slate-600"><input type="checkbox" checked={includeDeleted} onChange={(event) => setIncludeDeleted(event.target.checked)} /> 삭제 포함</label><div className="flex items-end justify-end gap-2"><span className="rounded bg-amber-100 px-3 py-2 text-xs font-black text-amber-800">승인 대기 {pendingQuery.data?.total ?? 0}</span><button type="button" onClick={() => { setNewMode(true); setSelectedId(null); }} className="inline-flex min-h-10 items-center gap-2 rounded bg-brand-blue px-4 text-xs font-black text-white"><Plus size={14} /> 신규 기사</button></div></div></section><div className="grid min-h-[620px] gap-4 xl:grid-cols-[minmax(0,1fr)_440px]"><section className="overflow-hidden rounded-lg border border-slate-200 bg-white">{techniciansQuery.isLoading ? <AdminLoading /> : techniciansQuery.isError ? <AdminError /> : <div className="overflow-x-auto"><table className="w-full text-left text-xs"><thead className="bg-slate-50 text-slate-500"><tr><Th>기사</Th><Th>유형/검증</Th><Th>지역</Th><Th>요금</Th><Th>실적</Th><Th>상태</Th></tr></thead><tbody>{techniciansQuery.data?.items.map((technician) => <tr key={technician.id} onClick={() => { setSelectedId(technician.id); setNewMode(false); }} className={`cursor-pointer border-t border-slate-100 hover:bg-blue-50 ${selectedId === technician.id ? 'bg-blue-50' : ''}`}><Td strong>{technician.displayName}{technician.seeded ? <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] text-amber-800">테스트 기사</span> : null}</Td><Td><span className="font-black">{technician.providerType === 'EXTERNAL' ? '외부 파트너' : 'BuildGraph'}</span><br /><StatusBadge status={technician.verificationStatus} /></Td><Td>{technician.serviceRegions.join(', ')}</Td><Td>{technician.assemblyFee.toLocaleString()}원</Td><Td>{technician.rating.toFixed(1)} · {technician.completedJobs}건</Td><Td><StatusBadge status={technician.deletedAt ? 'DELETED' : technician.status} /></Td></tr>)}</tbody></table></div>}</section><aside className="rounded-lg border border-slate-200 bg-white p-5">{newMode || selected ? <TechnicianForm technician={newMode ? null : selected} saving={saveMutation.isPending || approveMutation.isPending || rejectMutation.isPending} onSave={(payload) => saveMutation.mutate({ id: selected?.id, payload })} onApprove={selected?.providerType === 'EXTERNAL' && selected.verificationStatus === 'PENDING' ? () => approveMutation.mutate(selected.id) : undefined} onReject={selected?.providerType === 'EXTERNAL' && selected.verificationStatus === 'PENDING' ? () => { const reason = window.prompt('기사 신청 거절 사유를 입력하세요.'); if (reason?.trim()) rejectMutation.mutate({ id: selected.id, reason: reason.trim() }); } : undefined} onDelete={selected && !selected.deletedAt ? () => { if (window.confirm(`${selected.displayName} 기사를 삭제할까요?`)) deleteMutation.mutate(selected.id); } : undefined} onRestore={selected?.deletedAt ? () => restoreMutation.mutate(selected.id) : undefined} error={saveMutation.error ?? deleteMutation.error ?? restoreMutation.error ?? approveMutation.error ?? rejectMutation.error} /> : <EmptyAdminDetail text="관리할 기사를 선택하거나 신규 기사를 등록하세요." />}</aside></div></div>;
}

function TechnicianForm({ technician, saving, onSave, onApprove, onReject, onDelete, onRestore, error }: { technician: Technician | null; saving: boolean; onSave: (payload: TechnicianPayload) => void; onApprove?: () => void; onReject?: () => void; onDelete?: () => void; onRestore?: () => void; error?: unknown }) {
  const [draft, setDraft] = useState(() => technicianDraft(technician));
  useEffect(() => setDraft(technicianDraft(technician)), [technician]);
  const toggle = (key: 'serviceRegions' | 'serviceTypes', value: string) => setDraft((current) => ({ ...current, [key]: current[key].includes(value as never) ? current[key].filter((item) => item !== value) : [...current[key], value] }));
  const submit = () => onSave({ ...draft, specialties: draft.specialtiesText.split(',').map((item) => item.trim()).filter(Boolean), completedJobs: Number(draft.completedJobs), avgResponseMinutes: Number(draft.avgResponseMinutes), assemblyFee: Number(draft.assemblyFee), deliveryFee: Number(draft.deliveryFee), leadTimeDays: Number(draft.leadTimeDays), partsPriceAdjustment: Number(draft.partsPriceAdjustment), sortPriority: Number(draft.sortPriority), rating: Number(draft.rating) });
  return <div className="space-y-4"><div className="flex items-center justify-between"><div><h2 className="text-lg font-black text-slate-950">{technician ? technician.displayName : '신규 기사'}</h2><div className="mt-1 flex flex-wrap gap-1">{technician?.seeded ? <span className="rounded bg-amber-100 px-2 py-1 text-[10px] font-black text-amber-800">테스트 기사</span> : null}{technician ? <span className="rounded bg-blue-50 px-2 py-1 text-[10px] font-black text-brand-blue">{technician.providerType === 'EXTERNAL' ? '외부 파트너' : 'BuildGraph 기사'}</span> : null}</div></div><div className="space-y-1 text-right"><StatusBadge status={technician?.deletedAt ? 'DELETED' : draft.status} />{technician ? <StatusBadge status={technician.verificationStatus} /> : null}</div></div>{technician?.businessName || technician?.contactPhone ? <div className="rounded bg-slate-50 p-3 text-xs font-bold text-slate-600">{technician.businessName ?? '개인 기사'} · {technician.contactPhone ?? '-'}</div> : null}{technician?.rejectionReason ? <StateMessage type="warn" title="거절 사유" body={technician.rejectionReason} /> : null}<div className="grid gap-3 sm:grid-cols-2"><AdminInput label="기사명" value={draft.displayName} onChange={(value) => setDraft({ ...draft, displayName: value })} /><AdminInput label="이니셜" value={draft.initials} onChange={(value) => setDraft({ ...draft, initials: value })} /><AdminSelect label="상태" value={draft.status} onChange={(value) => setDraft({ ...draft, status: value as TechnicianPayload['status'] })} options={['ACTIVE', 'INACTIVE', 'SUSPENDED']} /><AdminInput label="평점" value={draft.rating} onChange={(value) => setDraft({ ...draft, rating: value })} /><AdminInput label="완료 건수" value={draft.completedJobs} onChange={(value) => setDraft({ ...draft, completedJobs: value })} /><AdminInput label="평균 응답(분)" value={draft.avgResponseMinutes} onChange={(value) => setDraft({ ...draft, avgResponseMinutes: value })} /><AdminInput label="조립비" value={draft.assemblyFee} onChange={(value) => setDraft({ ...draft, assemblyFee: value })} /><AdminInput label="배송비" value={draft.deliveryFee} onChange={(value) => setDraft({ ...draft, deliveryFee: value })} /><AdminInput label="예상 소요일" value={draft.leadTimeDays} onChange={(value) => setDraft({ ...draft, leadTimeDays: value })} /><AdminInput label="부품가 조정액" value={draft.partsPriceAdjustment} onChange={(value) => setDraft({ ...draft, partsPriceAdjustment: value })} /><AdminInput label="정렬 우선순위" value={draft.sortPriority} onChange={(value) => setDraft({ ...draft, sortPriority: value })} /><AdminInput label="전문 분야(쉼표)" value={draft.specialtiesText} onChange={(value) => setDraft({ ...draft, specialtiesText: value })} /></div><CheckboxGroup label="서비스 지역" values={REGIONS} selected={draft.serviceRegions} onToggle={(value) => toggle('serviceRegions', value)} /><CheckboxGroup label="서비스 방식" values={['FULL_SERVICE', 'ASSEMBLY_ONLY']} selected={draft.serviceTypes} onToggle={(value) => toggle('serviceTypes', value)} /><label className="flex items-center gap-2 text-xs font-black text-slate-700"><input type="checkbox" checked={draft.standardAsAccepted} onChange={(event) => setDraft({ ...draft, standardAsAccepted: event.target.checked })} /> BuildGraph 표준 AS 동의</label>{error ? <AdminMutationError error={error} /> : null}<div className="flex flex-wrap gap-2 border-t border-slate-200 pt-4">{onApprove ? <button type="button" disabled={saving} onClick={onApprove} className="inline-flex min-h-10 items-center gap-2 rounded bg-emerald-600 px-4 text-xs font-black text-white">승인</button> : null}{onReject ? <button type="button" disabled={saving} onClick={onReject} className="inline-flex min-h-10 items-center gap-2 rounded border border-red-200 px-4 text-xs font-black text-red-700">거절</button> : null}<button type="button" disabled={saving || Boolean(technician?.deletedAt)} onClick={submit} className="inline-flex min-h-10 items-center gap-2 rounded bg-brand-blue px-4 text-xs font-black text-white disabled:bg-slate-300"><Save size={14} /> 저장</button>{onDelete ? <button type="button" onClick={onDelete} className="inline-flex min-h-10 items-center gap-2 rounded border border-red-200 px-4 text-xs font-black text-red-700"><Trash2 size={14} /> 삭제</button> : null}{onRestore ? <button type="button" onClick={onRestore} className="inline-flex min-h-10 items-center gap-2 rounded border border-emerald-200 px-4 text-xs font-black text-emerald-700"><RotateCcw size={14} /> 복구</button> : null}</div></div>;
}

function technicianDraft(technician: Technician | null) { return { displayName: technician?.displayName ?? '', initials: technician?.initials ?? '', profileImageUrl: technician?.profileImageUrl ?? '', status: technician?.status ?? 'INACTIVE' as TechnicianPayload['status'], serviceRegions: technician?.serviceRegions ?? ['서울'], serviceTypes: technician?.serviceTypes ?? ['FULL_SERVICE', 'ASSEMBLY_ONLY'] as TechnicianPayload['serviceTypes'], specialtiesText: technician?.specialties.join(', ') ?? '', rating: String(technician?.rating ?? 0), completedJobs: String(technician?.completedJobs ?? 0), avgResponseMinutes: String(technician?.avgResponseMinutes ?? 0), assemblyFee: String(technician?.assemblyFee ?? 0), deliveryFee: String(technician?.deliveryFee ?? 0), leadTimeDays: String(technician?.leadTimeDays ?? 1), partsPriceAdjustment: String(technician?.partsPriceAdjustment ?? 0), sortPriority: String(technician?.sortPriority ?? 100), standardAsAccepted: technician?.standardAsAccepted ?? false }; }
function nextOperationalStatus(status: AssemblyRequestStatus): AssemblyRequestStatus | null { return ({ MATCHED: 'CONFIRMED', CONFIRMED: 'ASSEMBLING', ASSEMBLING: 'SHIPPED', SHIPPED: 'COMPLETED' } as Partial<Record<AssemblyRequestStatus, AssemblyRequestStatus>>)[status] ?? null; }
function TabButton({ active, onClick, icon, label }: { active: boolean; onClick: () => void; icon: React.ReactNode; label: string }) { return <button type="button" onClick={onClick} aria-pressed={active} className={`inline-flex min-h-11 items-center gap-2 border-b-2 px-4 text-sm font-black ${active ? 'border-brand-blue text-brand-blue' : 'border-transparent text-slate-500'}`}>{icon}{label}</button>; }
function AdminInput({ label, value, onChange, placeholder = '' }: { label: string; value: string; onChange: (value: string) => void; placeholder?: string }) { return <label className="text-xs font-black text-slate-600">{label}<input value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} className="mt-1 h-10 w-full rounded border border-slate-200 px-3 text-xs font-medium" /></label>; }
function AdminSelect({ label, value, onChange, options }: { label: string; value: string; onChange: (value: string) => void; options: string[] }) { return <label className="text-xs font-black text-slate-600">{label}<select value={value} onChange={(event) => onChange(event.target.value)} className="mt-1 h-10 w-full rounded border border-slate-200 px-3 text-xs font-medium">{options.map((option) => <option key={option || 'ALL'} value={option}>{option || '전체'}</option>)}</select></label>; }
function CheckboxGroup({ label, values, selected, onToggle }: { label: string; values: string[]; selected: readonly string[]; onToggle: (value: string) => void }) { return <fieldset><legend className="text-xs font-black text-slate-600">{label}</legend><div className="mt-2 flex flex-wrap gap-2">{values.map((value) => <label key={value} className="inline-flex items-center gap-1 rounded border border-slate-200 px-2 py-1.5 text-[11px] font-bold"><input type="checkbox" checked={selected.includes(value)} onChange={() => onToggle(value)} />{value}</label>)}</div></fieldset>; }
function MiniNumber({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) { return <label className="text-[11px] font-bold text-slate-500">{label}<input type="number" min="0" value={value} onChange={(event) => onChange(event.target.value)} className="mt-1 h-9 w-full rounded border border-slate-200 px-2 text-xs" /></label>; }
function Th({ children }: { children: React.ReactNode }) { return <th className="whitespace-nowrap px-4 py-3 font-black">{children}</th>; }
function Td({ children, strong = false }: { children: React.ReactNode; strong?: boolean }) { return <td className={`px-4 py-3 ${strong ? 'font-black text-slate-900' : 'font-bold text-slate-600'}`}>{children}</td>; }
function AdminLoading() { return <div className="p-8 text-center text-xs font-bold text-slate-500">불러오는 중...</div>; }
function AdminError() { return <StateMessage type="warn" title="조회 실패" body="관리자 데이터를 불러오지 못했습니다." />; }
function EmptyAdminDetail({ text }: { text: string }) { return <div className="grid min-h-[300px] place-items-center text-center text-sm font-bold text-slate-400">{text}</div>; }
function AdminMutationError({ error }: { error: unknown }) { return <div className="mt-2 rounded border border-red-200 bg-red-50 p-2 text-[11px] font-bold text-red-700">{error instanceof Error ? error.message : '요청 처리 실패'}</div>; }
