export default function ComingSoon({ page }: { page: string }) {
  return (
    <div className="p-8">
      <h1 className="text-2xl font-semibold mb-2">{page}</h1>
      <p className="text-slate-600">
        This page is on the roadmap. Track progress in the project backlog.
      </p>
    </div>
  );
}
