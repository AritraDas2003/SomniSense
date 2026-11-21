import { useState } from 'react'
import { ArrowLeft, ArrowRight, Check, UserRoundPlus } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import PageHeader from '../../components/common/PageHeader'
import { SourceBadge } from '../../components/common/StatusBadge'
import { useMockApi } from '../../services/api/apiConfig'
import { createPatient } from '../../services/patientService'

export default function AddPatientPage() {
  const navigate = useNavigate()
  const mockMode = useMockApi
  const [createdPatient, setCreatedPatient] = useState(null)
  const {
    register,
    handleSubmit: handleFormSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ defaultValues: { fullName: '', alias: '', sourceType: 'SIMULATED' } })

  async function handleSubmit(values) {
    try {
      const patient = await createPatient(values)
      setCreatedPatient(patient)
    } catch (submitError) {
      setError('root.server', {
        type: 'server',
        message: submitError.message || 'The patient could not be created.',
      })
    }
  }

  if (createdPatient) {
    return (
      <div className="page-stack">
        <PageHeader
          eyebrow="PATIENT REGISTRATION"
          title="Patient added"
          description={mockMode ? 'The mock service created this record and generated its patient code.' : 'The service created this record and returned its patient code.'}
        />
        <section className="panel success-panel">
          <div className="success-check"><Check size={24} /></div>
          <span className="section-kicker">RECORD CREATED</span>
          <h2>{createdPatient.alias || createdPatient.fullName}</h2>
          <div className="created-patient-meta"><span>Patient code</span><strong>{createdPatient.patientCode}</strong><SourceBadge sourceType={createdPatient.sourceType} /></div>
          <p>Assign an enrolled device to continue. No measurements have been created for this patient.</p>
          <div className="success-actions">
            <button className="button button-primary" onClick={() => navigate(`/devices?patientId=${encodeURIComponent(createdPatient.id)}`)}>Assign a device <ArrowRight size={16} /></button>
            <Link className="button button-outline" to={`/patients/${createdPatient.id}`}>View patient</Link>
          </div>
        </section>
      </div>
    )
  }

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="PATIENT REGISTRATION"
        title="Add a patient"
        description="Create a minimal patient record. The service generates the patient code."
        actions={<Link className="button button-outline" to="/patients"><ArrowLeft size={16} /> Back to patients</Link>}
      />
      <div className="form-page-grid">
        <form className="panel form-panel" onSubmit={handleFormSubmit(handleSubmit)} noValidate>
          <div className="form-panel-title"><div className="form-panel-icon"><UserRoundPlus size={19} /></div><div><h2>Patient details</h2><p>Only the basic information needed to identify this record.</p></div></div>
          <div className="form-grid">
            <label className="form-field form-field-wide">
              <span>Full name <b>*</b></span>
              <input
                className="form-input"
                autoComplete="name"
                placeholder="Enter patient's full name"
                aria-invalid={Boolean(errors.fullName)}
                aria-describedby={errors.fullName ? 'patient-name-error' : undefined}
                {...register('fullName', {
                  required: 'Enter the patient’s full name.',
                  minLength: { value: 2, message: 'Name must be at least 2 characters.' },
                  maxLength: { value: 150, message: 'Name must be 150 characters or fewer.' },
                })}
              />
              {errors.fullName && <small id="patient-name-error" className="field-error" role="alert">{errors.fullName.message}</small>}
            </label>
            <label className="form-field form-field-wide">
              <span>Preferred name or alias <small>Optional</small></span>
              <input
                className="form-input"
                placeholder="Name used in the workspace"
                aria-invalid={Boolean(errors.alias)}
                aria-describedby={errors.alias ? 'patient-alias-error' : undefined}
                {...register('alias', {
                  maxLength: { value: 100, message: 'Alias must be 100 characters or fewer.' },
                })}
              />
              {errors.alias && <small id="patient-alias-error" className="field-error" role="alert">{errors.alias.message}</small>}
            </label>
            <label className="form-field form-field-wide">
              <span>Monitoring source <b>*</b></span>
              <select
                className="select-control form-select"
                aria-invalid={Boolean(errors.sourceType)}
                aria-describedby={errors.sourceType ? 'patient-source-error' : undefined}
                {...register('sourceType', { required: 'Select a monitoring source.' })}
              >
                <option value="SIMULATED">Simulated — demo stream only</option>
                <option value="PHYSICAL">Physical — no live data until integrated</option>
              </select>
              {errors.sourceType && <small id="patient-source-error" className="field-error" role="alert">{errors.sourceType.message}</small>}
              <small className="field-help">Source type describes the device stream. It does not indicate clinical status.</small>
            </label>
          </div>
          {errors.root?.server && <div className="form-error" role="alert">{errors.root.server.message}</div>}
          <div className="form-actions">
            <Link className="button button-ghost" to="/patients">Cancel</Link>
            <button className="button button-primary" type="submit" disabled={isSubmitting}>{isSubmitting ? <><span className="spinner" /> Saving...</> : <>Create patient <ArrowRight size={16} /></>}</button>
          </div>
        </form>
        <aside className="form-aside">
          <span className="section-kicker">BEFORE YOU CONTINUE</span>
          <h3>Keep the record focused.</h3>
          <p>Additional contact or medical details are intentionally not collected in this first frontend milestone.</p>
          <div className="form-aside-line"><span className="aside-number">01</span><span>Patient codes are generated by the service.</span></div>
          <div className="form-aside-line"><span className="aside-number">02</span><span>Readings are not created during registration.</span></div>
          <div className="form-aside-line"><span className="aside-number">03</span><span>{mockMode ? 'All data here is mock data for UI development.' : 'Data is provided by the configured application API.'}</span></div>
        </aside>
      </div>
    </div>
  )
}
