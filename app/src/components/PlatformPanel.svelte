<script lang="ts">
  import RowTable from './RowTable.svelte';
  import { app, groovy, platform, platformDirty, platformSectionDoc, savePlatform, loadPlatform, openFile, confirm } from '../lib/store.svelte';
  import { newPlatformRow, partnerExtras, PLATFORM_FILES, PLATFORM_KEYS, type PlatformKey } from '../lib/platform';

  /**
   * The mocked platform data a script reads through ITApiFactory, as tables over the project's platform/*.json
   * files (docs/test-cases.md). A table's unsaved rows already go with each run; Save writes the file.
   */
  interface Props {
    onchange?: () => void;
  }
  let { onchange }: Props = $props();

  const SECTIONS: { key: PlatformKey; title: string; api: string; add: string; empty: string }[] = [
    { key: 'valueMappings', title: 'Value mappings', api: 'ValueMappingApi.getMappedValue', add: '+ Value mapping',
      empty: 'No value mappings: getMappedValue returns null.' },
    { key: 'credentials', title: 'Secure store', api: 'SecureStoreService.getUserCredential', add: '+ Credential',
      empty: 'No credentials: an unknown alias throws SecureStoreException.' },
    { key: 'partnerDirectory', title: 'Partner Directory', api: 'PartnerDirectoryService.getParameter', add: '+ Parameter',
      empty: 'No partners: getParameter returns null.' },
    { key: 'numberRanges', title: 'Number ranges', api: 'NumberRangeConfigurationService.getNextValuefromNumberRange', add: '+ Number range',
      empty: 'No number ranges.' },
  ];

  let overridden = $derived(Object.keys(groovy.casePlatform ?? {}));
  let extras = $derived(partnerExtras(platform.partnerDirectory.doc));

  async function reload() {
    const unsaved = PLATFORM_KEYS.filter(platformDirty);
    if (unsaved.length && !(await confirm(`Read the platform files again and drop the unsaved changes (${unsaved.map((k) => PLATFORM_FILES[k]).join(', ')})?`))) return;
    await loadPlatform(true);
  }

  async function save(key: PlatformKey) {
    await savePlatform(key).catch((e) => (app.error = (e as Error).message));
  }
</script>

<div class="platform">
  <p class="hint">What the script gets from <code>ITApiFactory</code>, from the project's <code>platform/</code> files. Changes apply to the
    next run at once; <b>Save</b> writes the file. Use test-only values here, never a tenant's real secrets.</p>
  {#each SECTIONS as s (s.key)}
    {@const sec = platform[s.key]}
    {@const dirty = platformDirty(s.key)}
    {@const problem = sec.loadError || platformSectionDoc(s.key).error}
    <section aria-label={s.title}>
      <header>
        <h3 title={s.api}>{s.title}{#if dirty}<span class="dirty" title="Unsaved changes: they apply to runs, the file does not have them yet">●</span>{/if}</h3>
        <span class="file mono" title={sec.exists ? PLATFORM_FILES[s.key] : PLATFORM_FILES[s.key] + ' does not exist yet'}>{PLATFORM_FILES[s.key].split('/').pop()}</span>
        <div class="spacer"></div>
        {#if sec.exists}<button class="link" onclick={() => openFile(PLATFORM_FILES[s.key])} title="Open the JSON file in the File tab">Open file</button>{/if}
        <button class="small" disabled={!app.project || !dirty || !!problem} onclick={() => save(s.key)}
          title={app.project ? 'Write ' + PLATFORM_FILES[s.key] : 'Open a project to save'} aria-label="Save {s.title}">Save</button>
      </header>
      {#if overridden.includes(s.key)}
        <p class="note">The open test case brings its own {s.title.toLowerCase()} (<code>platform.{s.key}</code> in <code>case.json</code>); its runs use those, not this table.</p>
      {/if}
      {#if sec.loadError}
        <p class="err">{PLATFORM_FILES[s.key]} cannot be shown as a table: {sec.loadError}. Fix it in the file.</p>
      {:else}
        {#if s.key === 'valueMappings'}
          <RowTable bind:rows={platform.valueMappings.rows} newRow={() => newPlatformRow('valueMappings')} addLabel={s.add} emptyText={s.empty} {onchange}
            columns={[
              { field: 'sourceAgency', label: 'Source agency' }, { field: 'sourceIdentifier', label: 'Source identifier' }, { field: 'sourceValue', label: 'Source value' },
              { field: 'targetAgency', label: 'Target agency' }, { field: 'targetIdentifier', label: 'Target identifier' }, { field: 'targetValue', label: 'Target value' },
            ]} />
        {:else if s.key === 'credentials'}
          <RowTable bind:rows={platform.credentials.rows} newRow={() => newPlatformRow('credentials')} addLabel={s.add} emptyText={s.empty} {onchange}
            columns={[{ field: 'alias', label: 'Alias' }, { field: 'username', label: 'User' }, { field: 'password', label: 'Password' }]} />
          {#if platform.credentials.rows.some((r) => Object.keys(r.rest).length > 0)}
            <p class="note">Some credentials have more in the file (properties, OAuth2 tokens): {platform.credentials.rows.filter((r) => Object.keys(r.rest).length > 0)
              .map((r) => `${r.alias}: ${Object.keys(r.rest).join(', ')}`).join('; ')}. Edit those in the file; they are kept.</p>
          {/if}
        {:else if s.key === 'partnerDirectory'}
          <RowTable bind:rows={platform.partnerDirectory.rows} newRow={() => newPlatformRow('partnerDirectory')} addLabel={s.add} emptyText={s.empty} {onchange}
            columns={[{ field: 'partner', label: 'Partner ID' }, { field: 'name', label: 'Parameter' },
              { field: 'value', label: 'Value', disabled: (r) => r.binary !== null,
                placeholder: (r) => (r.binary ? 'binary: ' + (r.binary.file ?? 'Base64') + ' (edit in the file)' : '') }]} />
          {#if extras.length}
            <p class="note">Also in the file, kept as they are: {extras.map((e) => `${e.partner}: ${[e.alternativePartners ? e.alternativePartners + ' alternative partner(s)' : '',
              e.authorizedUsers ? e.authorizedUsers + ' authorized user(s)' : ''].filter(Boolean).join(', ')}`).join('; ')}.</p>
          {/if}
        {:else}
          <RowTable bind:rows={platform.numberRanges.rows} newRow={() => newPlatformRow('numberRanges')} addLabel={s.add} emptyText={s.empty} {onchange}
            columns={[{ field: 'name', label: 'Name' }, { field: 'next', label: 'Next value', type: 'number' }, { field: 'fieldLength', label: 'Field length', type: 'number' },
              { field: 'min', label: 'Minimum', type: 'number' }, { field: 'max', label: 'Maximum', type: 'number' }, { field: 'rotate', label: 'Rotate', type: 'checkbox' }]} />
        {/if}
        {#if !sec.loadError && platformSectionDoc(s.key).error}<p class="err">{platformSectionDoc(s.key).error}</p>{/if}
      {/if}
    </section>
  {/each}
  <section aria-label="Keystore">
    <header>
      <h3 title="KeystoreService">Keystore</h3>
      <span class="file mono">keystore.json</span>
      <div class="spacer"></div>
      {#if platform.keystoreExists}<button class="link" onclick={() => openFile(PLATFORM_FILES.keystore)}>Open file</button>{/if}
    </header>
    <p class="note">{platform.keystoreExists ? 'keystore.json names the keystore file (PKCS12 or JKS) and its passwords; it is edited as a file.'
      : 'No keystore.json: the keystore is empty. It names a keystore file next to it and is edited as a file.'}</p>
  </section>
  {#if app.project}
    <button class="small reload" onclick={reload} title="Read the platform files again; unsaved changes in the tables are dropped">↻ Reload files</button>
  {/if}
</div>

<style>
  .platform { padding: 8px 10px; display: flex; flex-direction: column; gap: 10px; overflow: auto; height: 100%; box-sizing: border-box; }
  section { display: flex; flex-direction: column; gap: 5px; }
  header { display: flex; align-items: baseline; gap: 6px; }
  h3 { margin: 0; font-size: 12px; font-weight: 600; white-space: nowrap; }
  .file { font-size: 10px; color: var(--muted); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .spacer { flex: 1; }
  .dirty { color: var(--accent); margin-left: 4px; }
  .hint, .note { margin: 0; font-size: 11px; color: var(--muted); }
  .err { margin: 0; font-size: 11px; color: var(--err); }
  .link { border: none; background: none; padding: 0; white-space: nowrap; font-size: 11px; color: var(--accent); text-decoration: underline; cursor: pointer; }
  .reload { align-self: flex-start; }
</style>
