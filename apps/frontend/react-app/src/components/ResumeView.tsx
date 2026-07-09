import type { Resume } from "../api";

export function ResumeView({ resume }: { resume: Resume }) {
  const contact = resume.contact;
  return (
    <article className="resume">
      <header className="resume-header">
        <h2 className="resume-name">{resume.name}</h2>
        {resume.headline && <p className="resume-headline">{resume.headline}</p>}
        {(contact?.email || contact?.location) && (
          <div className="chips">
            {contact?.email && (
              <a className="chip chip-link" href={`mailto:${contact.email}`}>
                {contact.email}
              </a>
            )}
            {contact?.location && <span className="chip">{contact.location}</span>}
          </div>
        )}
      </header>

      {resume.summary && <p className="resume-summary">{resume.summary}</p>}

      {resume.experience && resume.experience.length > 0 && (
        <section className="resume-section">
          <h3 className="section-title">Experience</h3>
          <ol className="timeline">
            {resume.experience.map((job) => (
              <li className="timeline-item" key={`${job.company}-${job.period}`}>
                <div className="timeline-head">
                  <span className="timeline-role">{job.role}</span>
                  <span className="timeline-period">{job.period}</span>
                </div>
                <p className="timeline-company">{job.company}</p>
                {job.highlights.length > 0 && (
                  <ul className="timeline-highlights">
                    {job.highlights.map((highlight) => (
                      <li key={highlight}>{highlight}</li>
                    ))}
                  </ul>
                )}
              </li>
            ))}
          </ol>
        </section>
      )}

      {resume.skills && resume.skills.length > 0 && (
        <section className="resume-section">
          <h3 className="section-title">Skills</h3>
          <div className="chips">
            {resume.skills.map((skill) => (
              <span className="chip chip-accent" key={skill}>
                {skill}
              </span>
            ))}
          </div>
        </section>
      )}
    </article>
  );
}
