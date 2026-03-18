import { Layout } from '../components/Layout'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../components/ui/tabs'
import { FieldConfigEditor } from '../components/admin/FieldConfigEditor'
import { ComponentDefaultsForm } from '../components/admin/ComponentDefaultsForm'

export function AdminSettingsPage() {
  return (
    <Layout>
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-semibold tracking-tight">Admin Settings</h1>
        </div>

        <Tabs defaultValue="field-config">
          <TabsList>
            <TabsTrigger value="field-config">Field Configuration</TabsTrigger>
            <TabsTrigger value="component-defaults">Component Defaults</TabsTrigger>
          </TabsList>

          <TabsContent value="field-config" className="mt-4">
            <div className="rounded-lg border p-6 space-y-2">
              <h2 className="text-lg font-semibold">Field Configuration</h2>
              <p className="text-sm text-muted-foreground">
                Controls which fields are editable, readonly, or hidden across the registry.
              </p>
              <div className="pt-2">
                <FieldConfigEditor />
              </div>
            </div>
          </TabsContent>

          <TabsContent value="component-defaults" className="mt-4">
            <div className="rounded-lg border p-6 space-y-2">
              <h2 className="text-lg font-semibold">Component Defaults</h2>
              <p className="text-sm text-muted-foreground">
                Default values applied to new components when fields are not explicitly set.
              </p>
              <div className="pt-2">
                <ComponentDefaultsForm />
              </div>
            </div>
          </TabsContent>
        </Tabs>
      </div>
    </Layout>
  )
}
