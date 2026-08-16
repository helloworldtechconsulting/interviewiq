// =============================================================================
// SettingsPage.tsx — Company profile + logo upload
// =============================================================================

import { useRef, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Building2, Upload, Loader2, Save } from "lucide-react";

import { companyApi } from "@/api/modules/company";
import { queryKeys } from "@/lib/queryKeys";
import { AppError } from "@/api/client";
import { PageHeader } from "@/components/common/PageHeader";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { initials, uploadToS3 } from "@/lib/utils";

// ── Schema ────────────────────────────────────────────────────────────────────

const settingsSchema = z.object({
  name: z.string().min(2, "Company name must be at least 2 characters"),
  website: z
    .string()
    .url("Must be a valid URL (include https://)")
    .optional()
    .or(z.literal("")),
  industry: z.string().optional(),
});

type SettingsForm = z.infer<typeof settingsSchema>;

// ── Component ─────────────────────────────────────────────────────────────────

export function SettingsPage() {
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [logoPreview, setLogoPreview] = useState<string | null>(null);

  // ── Queries ────────────────────────────────────────────────────────────────

  const { data: company, isLoading } = useQuery({
    queryKey: queryKeys.company.detail(),
    queryFn: companyApi.getMyCompany,
  });

  // ── Form ───────────────────────────────────────────────────────────────────

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty },
  } = useForm<SettingsForm>({
    resolver: zodResolver(settingsSchema),
    values: company
      ? {
          name: company.name,
          website: company.website ?? "",
          industry: company.industry ?? "",
        }
      : undefined,
  });

  // ── Mutations ──────────────────────────────────────────────────────────────

  const updateMutation = useMutation({
    mutationFn: (data: SettingsForm) =>
      companyApi.update({
        name: data.name,
        website: data.website || undefined,
        industry: data.industry || undefined,
      }),
    onSuccess() {
      toast.success("Company profile updated.");
      void qc.invalidateQueries({ queryKey: queryKeys.company.all() });
    },
    onError(error) {
      if (error instanceof AppError && error.fieldErrors) {
        Object.entries(error.fieldErrors).forEach(([field, message]) => {
          setError(field as keyof SettingsForm, { message });
        });
      } else {
        toast.error(
          error instanceof AppError ? error.message : "Update failed.",
        );
      }
    },
  });

  // ── Logo upload ────────────────────────────────────────────────────────────

  async function handleLogoUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;

    // Local preview
    const reader = new FileReader();
    reader.onload = (ev) =>
      setLogoPreview(ev.target?.result as string);
    reader.readAsDataURL(file);

    try {
      setUploadProgress(0);
      const { uploadUrl } = await companyApi.getLogoUploadUrl(
        file.name,
        file.type || "image/png",
      );
      await uploadToS3(uploadUrl, file, setUploadProgress);
      toast.success("Logo uploaded successfully.");
      void qc.invalidateQueries({ queryKey: queryKeys.company.all() });
    } catch {
      toast.error("Logo upload failed. Please try again.");
      setLogoPreview(null);
    } finally {
      setUploadProgress(null);
      e.target.value = "";
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Settings"
        description="Manage your company profile and preferences."
      />

      {isLoading ? (
        <div className="space-y-4">
          <div className="h-40 animate-pulse rounded-lg bg-muted" />
          <div className="h-64 animate-pulse rounded-lg bg-muted" />
        </div>
      ) : (
        <div className="grid gap-6 lg:grid-cols-3">
          {/* ── Profile form ─────────────────────────────────────────────── */}
          <div className="lg:col-span-2">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-base">
                  <Building2 className="h-4 w-4" />
                  Company Profile
                </CardTitle>
              </CardHeader>
              <Separator />
              <form
                onSubmit={handleSubmit((d) => updateMutation.mutate(d))}
                noValidate
              >
                <CardContent className="space-y-4 pt-4">
                  {/* Company name */}
                  <div className="space-y-1.5">
                    <Label htmlFor="name">Company name *</Label>
                    <Input
                      id="name"
                      placeholder="Acme Corp"
                      {...register("name")}
                    />
                    {errors.name && (
                      <p className="text-xs text-destructive">
                        {errors.name.message}
                      </p>
                    )}
                  </div>

                  {/* Website */}
                  <div className="space-y-1.5">
                    <Label htmlFor="website">Website</Label>
                    <Input
                      id="website"
                      type="url"
                      placeholder="https://yourcompany.com"
                      {...register("website")}
                    />
                    {errors.website && (
                      <p className="text-xs text-destructive">
                        {errors.website.message}
                      </p>
                    )}
                  </div>

                  {/* Industry */}
                  <div className="space-y-1.5">
                    <Label htmlFor="industry">Industry</Label>
                    <Input
                      id="industry"
                      placeholder="e.g. SaaS, Fintech, E-commerce"
                      {...register("industry")}
                    />
                  </div>
                </CardContent>

                <div className="flex justify-end px-6 pb-6">
                  <Button
                    type="submit"
                    disabled={updateMutation.isPending || !isDirty}
                  >
                    {updateMutation.isPending ? (
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    ) : (
                      <Save className="mr-2 h-4 w-4" />
                    )}
                    Save Changes
                  </Button>
                </div>
              </form>
            </Card>
          </div>

          {/* ── Logo ─────────────────────────────────────────────────────── */}
          <div className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Company Logo</CardTitle>
              </CardHeader>
              <Separator />
              <CardContent className="flex flex-col items-center gap-4 pt-4">
                {/* Preview */}
                <Avatar className="h-24 w-24">
                  {(logoPreview ?? company?.logoS3Key) && (
                    <AvatarImage
                      src={logoPreview ?? company?.logoS3Key ?? undefined}
                      alt={company?.name}
                    />
                  )}
                  <AvatarFallback className="text-2xl font-bold">
                    {company ? initials(company.name) : "?"}
                  </AvatarFallback>
                </Avatar>

                <p className="text-center text-sm text-muted-foreground">
                  PNG or JPG, max 2 MB. Displayed on interview invites.
                </p>

                {/* Upload button */}
                <input
                  ref={fileRef}
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  className="sr-only"
                  onChange={handleLogoUpload}
                  disabled={uploadProgress !== null}
                />
                <Button
                  variant="outline"
                  className="w-full"
                  onClick={() => fileRef.current?.click()}
                  disabled={uploadProgress !== null}
                >
                  {uploadProgress !== null ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Uploading {uploadProgress}%
                    </>
                  ) : (
                    <>
                      <Upload className="mr-2 h-4 w-4" />
                      {company?.logoS3Key ? "Replace Logo" : "Upload Logo"}
                    </>
                  )}
                </Button>

                {uploadProgress !== null && (
                  <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className="h-full bg-primary transition-all"
                      style={{ width: `${uploadProgress}%` }}
                    />
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Danger zone placeholder */}
            <Card className="border-destructive/30">
              <CardHeader>
                <CardTitle className="text-base text-destructive">
                  Danger Zone
                </CardTitle>
              </CardHeader>
              <Separator />
              <CardContent className="pt-4">
                <p className="mb-3 text-sm text-muted-foreground">
                  Contact support to delete your account or export your data.
                </p>
                <Button
                  variant="destructive"
                  size="sm"
                  className="w-full"
                  onClick={() =>
                    window.open("mailto:support@interviewengine.ai", "_blank")
                  }
                >
                  Contact Support
                </Button>
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </div>
  );
}
