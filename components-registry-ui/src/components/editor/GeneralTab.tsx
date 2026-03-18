import { useEffect } from 'react'
import { UseFormReturn } from 'react-hook-form'
import { Label } from '../ui/label'
import { Input } from '../ui/input'
import { Switch } from '../ui/switch'
import { EnumSelect } from '../ui/EnumSelect'
import { PeopleInput } from '../ui/PeopleInput'
import { FieldOverrideInline } from './FieldOverrideInline'
import type { ComponentDetail } from '../../lib/types'

export interface GeneralFormValues {
  displayName: string
  componentOwner: string
  productType: string
  system: string
  clientCode: string
  solution: boolean
  archived: boolean
}

interface GeneralTabProps {
  component: ComponentDetail
  form: UseFormReturn<GeneralFormValues>
  isNew?: boolean
}

export function GeneralTab({ component, form, isNew = false }: GeneralTabProps) {
  const {
    register,
    setValue,
    watch,
    formState: { errors },
  } = form

  const solution = watch('solution')
  const archived = watch('archived')
  const productType = watch('productType')
  const componentOwner = watch('componentOwner')

  useEffect(() => {
    setValue('displayName', component.displayName ?? '')
    setValue('componentOwner', component.componentOwner ?? '')
    setValue('productType', component.productType ?? '')
    setValue('system', component.system.join(', '))
    setValue('clientCode', component.clientCode ?? '')
    setValue('solution', component.solution ?? false)
    setValue('archived', component.archived)
  }, [component, setValue])

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {/* Name - readonly after create */}
        <div className="space-y-1.5">
          <Label htmlFor="name">Name</Label>
          <Input
            id="name"
            value={component.name}
            disabled
            className="bg-muted"
          />
          {!isNew && (
            <p className="text-xs text-muted-foreground">Component name cannot be changed after creation.</p>
          )}
        </div>

        {/* Display Name */}
        <div className="space-y-1.5">
          <Label htmlFor="displayName">Display Name</Label>
          <Input
            id="displayName"
            placeholder="Human-readable name"
            {...register('displayName')}
          />
          {errors.displayName && (
            <p className="text-xs text-destructive">{errors.displayName.message}</p>
          )}
        </div>

        {/* Component Owner */}
        <div className="space-y-1.5">
          <Label htmlFor="componentOwner">Component Owner</Label>
          <PeopleInput
            value={componentOwner}
            onChange={(val) => setValue('componentOwner', val)}
          />
          <FieldOverrideInline componentId={component.id} fieldPath="componentOwner" />
        </div>

        {/* Product Type */}
        <div className="space-y-1.5">
          <Label htmlFor="productType">Product Type</Label>
          <EnumSelect
            fieldPath="productType"
            value={productType || ''}
            onValueChange={(val) => setValue('productType', val)}
            placeholder="Select product type"
          />
        </div>

        {/* System (comma-separated) */}
        <div className="space-y-1.5">
          <Label htmlFor="system">System(s)</Label>
          <Input
            id="system"
            placeholder="SYSTEM1, SYSTEM2"
            {...register('system')}
          />
          <p className="text-xs text-muted-foreground">Comma-separated list of systems.</p>
          <FieldOverrideInline componentId={component.id} fieldPath="system" />
        </div>

        {/* Client Code */}
        <div className="space-y-1.5">
          <Label htmlFor="clientCode">Client Code</Label>
          <Input
            id="clientCode"
            placeholder="CLIENT_CODE"
            {...register('clientCode')}
          />
          <FieldOverrideInline componentId={component.id} fieldPath="clientCode" />
        </div>
      </div>

      {/* Toggles */}
      <div className="flex flex-wrap gap-6">
        <div className="flex items-center gap-3">
          <Switch
            id="solution"
            checked={solution}
            onCheckedChange={(checked) => setValue('solution', checked)}
          />
          <Label htmlFor="solution" className="cursor-pointer">Solution</Label>
        </div>

        <div className="flex items-center gap-3">
          <Switch
            id="archived"
            checked={archived}
            onCheckedChange={(checked) => setValue('archived', checked)}
          />
          <Label htmlFor="archived" className="cursor-pointer">Archived</Label>
        </div>
      </div>

      {/* Metadata info */}
      {component.parentComponentName && (
        <div className="rounded-md bg-muted px-4 py-3 text-sm">
          <span className="font-medium">Parent Component:</span>{' '}
          <span className="text-muted-foreground">{component.parentComponentName}</span>
        </div>
      )}

      {component.createdAt && (
        <div className="flex gap-6 text-xs text-muted-foreground">
          <span>Created: {new Date(component.createdAt).toLocaleString()}</span>
          {component.updatedAt && (
            <span>Updated: {new Date(component.updatedAt).toLocaleString()}</span>
          )}
          <span>Version: {component.version}</span>
        </div>
      )}
    </div>
  )
}
