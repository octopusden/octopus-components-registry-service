import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Plus } from 'lucide-react'
import { Button } from './ui/button'
import { Input } from './ui/input'
import { Label } from './ui/label'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
  DialogClose,
} from './ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './ui/select'
import { useCreateComponent } from '../hooks/useComponent'
import { useToast } from '../hooks/use-toast'
import { ApiError } from '../lib/api'

const createSchema = z.object({
  name: z
    .string()
    .min(1, 'Name is required')
    .regex(/^[a-zA-Z0-9_\-./]+$/, 'Name can only contain letters, digits, _, -, ., /'),
  displayName: z.string().optional(),
  componentOwner: z.string().optional(),
  productType: z.string().optional(),
  system: z.string().optional(),
})

type CreateFormValues = z.infer<typeof createSchema>

const PRODUCT_TYPES = ['PRODUCT', 'LIBRARY', 'TOOL', 'INFRASTRUCTURE', 'OTHER']

interface CreateComponentDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function CreateComponentDialog({ open, onOpenChange }: CreateComponentDialogProps) {
  const navigate = useNavigate()
  const createMutation = useCreateComponent()
  const { toast } = useToast()
  const [productType, setProductType] = useState('')

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<CreateFormValues>({
    resolver: zodResolver(createSchema),
    defaultValues: {
      name: '',
      displayName: '',
      componentOwner: '',
      productType: '',
      system: '',
    },
  })

  function handleOpenChange(open: boolean) {
    if (!open) {
      reset()
      setProductType('')
    }
    onOpenChange(open)
  }

  async function onSubmit(values: CreateFormValues) {
    const systemArray = values.system
      ? values.system.split(',').map((s) => s.trim()).filter(Boolean)
      : []

    try {
      const component = await createMutation.mutateAsync({
        name: values.name,
        displayName: values.displayName || undefined,
        componentOwner: values.componentOwner || undefined,
        productType: (productType && productType !== '__none__') ? productType : undefined,
        system: systemArray.length > 0 ? systemArray : undefined,
      })
      toast({ title: 'Component created', description: `"${component.name}" was created.` })
      handleOpenChange(false)
      navigate(`/components/${component.id}`)
    } catch (err) {
      let message = err instanceof Error ? err.message : String(err)
      if (err instanceof ApiError && err.status === 409) {
        message = 'A component with this name already exists.'
      }
      toast({
        title: 'Failed to create component',
        description: message,
        variant: 'destructive',
      })
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Create Component</DialogTitle>
          <DialogDescription>
            Add a new component to the registry. The name cannot be changed after creation.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="create-name">
              Name <span className="text-destructive">*</span>
            </Label>
            <Input
              id="create-name"
              placeholder="my-component"
              autoFocus
              {...register('name')}
            />
            {errors.name && (
              <p className="text-xs text-destructive">{errors.name.message}</p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="create-displayName">Display Name</Label>
            <Input
              id="create-displayName"
              placeholder="My Component"
              {...register('displayName')}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="create-componentOwner">Component Owner</Label>
            <Input
              id="create-componentOwner"
              placeholder="owner@example.com"
              {...register('componentOwner')}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="create-productType">Product Type</Label>
            <Select value={productType} onValueChange={setProductType}>
              <SelectTrigger id="create-productType">
                <SelectValue placeholder="Select product type" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">None</SelectItem>
                {PRODUCT_TYPES.map((pt) => (
                  <SelectItem key={pt} value={pt}>
                    {pt}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="create-system">System(s)</Label>
            <Input
              id="create-system"
              placeholder="SYSTEM1, SYSTEM2"
              {...register('system')}
            />
            <p className="text-xs text-muted-foreground">Comma-separated list of systems.</p>
          </div>

          <DialogFooter>
            <DialogClose asChild>
              <Button type="button" variant="outline">Cancel</Button>
            </DialogClose>
            <Button type="submit" disabled={isSubmitting || createMutation.isPending}>
              <Plus className="h-4 w-4" />
              Create
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export function CreateComponentButton() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button onClick={() => setOpen(true)}>
        <Plus className="h-4 w-4" />
        New Component
      </Button>
      <CreateComponentDialog open={open} onOpenChange={setOpen} />
    </>
  )
}
