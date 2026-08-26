// ComfyWorkflowBuilder（对标 android transport/comfyui/ComfyWorkflowBuilder.kt）
// 构造 ComfyUI 工作流 JSON。
//
// ComfyUI 工作流格式：节点 ID 为键，值为 `{class_type, inputs}` 对象。
// 节点间引用为 `["<node_id>", <output_index>]` 数组。

import { AgentConfig } from '../../protocol/Agent';

// 默认参数（与 Android/iOS 三端一致）
const DEFAULT_CHECKPOINT: string = 'v1-5-pruned-emaonly.safetensors';
const DEFAULT_CFG: number = 7.0;
const DEFAULT_STEPS: number = 20;
const DEFAULT_NEGATIVE: string = 'bad quality, blurry';
const DEFAULT_WIDTH: number = 512;
const DEFAULT_HEIGHT: number = 512;
const DEFAULT_BATCH_SIZE: number = 1;
const DEFAULT_SAMPLER: string = 'euler';
const DEFAULT_SCHEDULER: string = 'normal';

// 节点 ID 常量（与 ComfyUI 默认导出保持一致）
const NODE_KSAMPLER: string = '3';
const NODE_CHECKPOINT_LOADER: string = '4';
const NODE_EMPTY_LATENT: string = '5';
const NODE_POSITIVE_PROMPT: string = '6';
const NODE_NEGATIVE_PROMPT: string = '7';
const NODE_VAE_DECODE: string = '8';
const NODE_SAVE_IMAGE: string = '9';

export class ComfyWorkflowBuilder {
  /**
   * 构造默认文生图工作流。
   *
   * AgentConfig 字段语义复用：
   * - model       → checkpoint 文件名（如 v1-5-pruned-emaonly.safetensors）
   * - temperature → cfg scale（默认 7.0）
   * - maxTokens   → steps 采样步数（默认 20）
   * - systemPrompt → 负向提示词（negative prompt）
   *
   * 节点结构：
   * - "3": KSampler（采样器）
   * - "4": CheckpointLoaderSimple（模型加载）
   * - "5": EmptyLatentImage（空 latent）
   * - "6"/"7": CLIPTextEncode 正/负向提示词
   * - "8": VAEDecode（VAE 解码）
   * - "9": SaveImage（保存图片）
   */
  static buildTextToImageWorkflow(prompt: string, config: AgentConfig): Record<string, Object> {
    const checkpoint: string = config.model.length > 0 ? config.model : DEFAULT_CHECKPOINT;
    const cfg: number = config.temperature > 0 ? config.temperature : DEFAULT_CFG;
    const steps: number = config.maxTokens > 0 ? config.maxTokens : DEFAULT_STEPS;
    const negative: string = config.systemPrompt.length > 0 ? config.systemPrompt : DEFAULT_NEGATIVE;
    const seed: number = Date.now() % 1000000000;

    const workflow: Record<string, Object> = {};
    workflow[NODE_KSAMPLER] = ComfyWorkflowBuilder.buildKSampler(seed, steps, cfg);
    workflow[NODE_CHECKPOINT_LOADER] = ComfyWorkflowBuilder.buildCheckpointLoader(checkpoint);
    workflow[NODE_EMPTY_LATENT] = ComfyWorkflowBuilder.buildEmptyLatent();
    workflow[NODE_POSITIVE_PROMPT] = ComfyWorkflowBuilder.buildClipTextEncode(prompt);
    workflow[NODE_NEGATIVE_PROMPT] = ComfyWorkflowBuilder.buildClipTextEncode(negative);
    workflow[NODE_VAE_DECODE] = ComfyWorkflowBuilder.buildVaeDecode();
    workflow[NODE_SAVE_IMAGE] = ComfyWorkflowBuilder.buildSaveImage();
    return workflow;
  }

  private static buildKSampler(seed: number, steps: number, cfg: number): Record<string, Object> {
    const inputs: Record<string, Object> = {};
    inputs['seed'] = seed;
    inputs['steps'] = steps;
    inputs['cfg'] = cfg;
    inputs['sampler_name'] = DEFAULT_SAMPLER;
    inputs['scheduler'] = DEFAULT_SCHEDULER;
    inputs['denoise'] = 1.0;
    inputs['model'] = ComfyWorkflowBuilder.nodeRef(NODE_CHECKPOINT_LOADER, 0);
    inputs['positive'] = ComfyWorkflowBuilder.nodeRef(NODE_POSITIVE_PROMPT, 0);
    inputs['negative'] = ComfyWorkflowBuilder.nodeRef(NODE_NEGATIVE_PROMPT, 0);
    inputs['latent_image'] = ComfyWorkflowBuilder.nodeRef(NODE_EMPTY_LATENT, 0);

    const node: Record<string, Object> = {};
    node['class_type'] = 'KSampler';
    node['inputs'] = inputs;
    return node;
  }

  private static buildCheckpointLoader(checkpoint: string): Record<string, Object> {
    const inputs: Record<string, Object> = {};
    inputs['ckpt_name'] = checkpoint;

    const node: Record<string, Object> = {};
    node['class_type'] = 'CheckpointLoaderSimple';
    node['inputs'] = inputs;
    return node;
  }

  private static buildEmptyLatent(): Record<string, Object> {
    const inputs: Record<string, Object> = {};
    inputs['width'] = DEFAULT_WIDTH;
    inputs['height'] = DEFAULT_HEIGHT;
    inputs['batch_size'] = DEFAULT_BATCH_SIZE;

    const node: Record<string, Object> = {};
    node['class_type'] = 'EmptyLatentImage';
    node['inputs'] = inputs;
    return node;
  }

  private static buildClipTextEncode(text: string): Record<string, Object> {
    const inputs: Record<string, Object> = {};
    inputs['text'] = text;
    inputs['clip'] = ComfyWorkflowBuilder.nodeRef(NODE_CHECKPOINT_LOADER, 1);

    const node: Record<string, Object> = {};
    node['class_type'] = 'CLIPTextEncode';
    node['inputs'] = inputs;
    return node;
  }

  private static buildVaeDecode(): Record<string, Object> {
    const inputs: Record<string, Object> = {};
    inputs['samples'] = ComfyWorkflowBuilder.nodeRef(NODE_KSAMPLER, 0);
    inputs['vae'] = ComfyWorkflowBuilder.nodeRef(NODE_CHECKPOINT_LOADER, 2);

    const node: Record<string, Object> = {};
    node['class_type'] = 'VAEDecode';
    node['inputs'] = inputs;
    return node;
  }

  private static buildSaveImage(): Record<string, Object> {
    const inputs: Record<string, Object> = {};
    inputs['filename_prefix'] = 'ComfyUI';
    inputs['images'] = ComfyWorkflowBuilder.nodeRef(NODE_VAE_DECODE, 0);

    const node: Record<string, Object> = {};
    node['class_type'] = 'SaveImage';
    node['inputs'] = inputs;
    return node;
  }

  /** 节点引用数组：["<node_id>", <output_index>]。 */
  private static nodeRef(nodeId: string, outputIndex: number): (string | number)[] {
    const ref: (string | number)[] = [];
    ref.push(nodeId);
    ref.push(outputIndex);
    return ref;
  }
}
